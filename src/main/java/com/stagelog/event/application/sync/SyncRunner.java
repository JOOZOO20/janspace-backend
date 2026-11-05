package com.stagelog.event.application.sync;

import com.stagelog.ops.application.JobLeases;
import com.stagelog.ops.application.JobLeases.Holder;
import com.stagelog.ops.application.OperatorAlert;
import com.stagelog.ops.application.SyncRunRecorder;
import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 동기화 실행 틀: Lease 획득 -> sync_runs 시작 -> 본문 -> OK/FAILED 종료 -> Lease 해제. 본문 예외는 밖으로 내지 않고 FAILED 기록 +
 * 운영자 알림으로 바꾼다 (스케줄러·관리자 트리거가 같은 틀을 탄다). 요약의 URL 에 섞인 키 값은 가린다(Spring 7 의 RestClient 예외
 * 메시지는 쿼리스트링을 이미 잘라내므로, 다른 경로로 들어오는 메시지에 대한 대비다).
 * 같은 공급원 동기화는 서버가 여러 대여도 한 번에 하나만 돈다(job_leases 의 sync:{source} 행). 공급원이 다른 동기화가 같은 행사를
 * 동시에 접는 경우는 ApplySourcesUseCase 가 행사 행 잠금으로 직렬화해 "(event, field) 당 PENDING 1건" 규칙을 지킨다.
 * jan.sync.lease.enabled=false 면 이전 동작: JVM 안 CAS 하나로 모든 공급원을 통틀어 하나만 돈다.
 */
@Slf4j
@Component
public class SyncRunner {

    private static final java.util.regex.Pattern SECRET_PARAM =
            java.util.regex.Pattern.compile("(?i)(apikey|service|publishedKey|x-api-key)=[^&\\s\"']+");
    static final String LEASE_LOST = "LEASE_LOST";
    // owner_id 컬럼(100자)에 맞춘다: 호스트 50 + pid + uuid 36
    private static final String HOST = hostname();

    private final SyncRunRecorder recorder;
    private final OperatorAlert operatorAlert;
    private final JobLeases leases;
    private final SyncLeaseProperties props;
    // {hostname}:{pid}:{uuid}. 인스턴스마다 다르다
    private final String ownerId;
    // 동기화가 스케줄러 스레드를 점유하므로 Heartbeat 는 별도 스레드에서 돈다
    private final ScheduledExecutorService heartbeats;
    // 본문은 호출 스레드에서 돌므로 ensureHeld 가 같은 스레드의 Lease 를 찾는다
    private final ThreadLocal<Held> held = new ThreadLocal<>();
    // 본문이 항목 실패를 기록할 실행 (recordItemFailure)
    private final ThreadLocal<CurrentRun> currentRun = new ThreadLocal<>();
    // jan.sync.lease.enabled=false 일 때만 쓴다. JVM 안에서만 막는다
    private final AtomicReference<String> running = new AtomicReference<>();

    public SyncRunner(SyncRunRecorder recorder, OperatorAlert operatorAlert, JobLeases leases, SyncLeaseProperties props) {
        this.recorder = recorder;
        this.operatorAlert = operatorAlert;
        this.leases = leases;
        this.props = props;
        this.ownerId = HOST + ":" + ProcessHandle.current().pid() + ":" + UUID.randomUUID();
        this.heartbeats = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sync-lease-heartbeat");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 실행 결과. source 는 sync_runs.source 이름(제공자 이름), summary 는 사람이 읽는 한 줄 요약.
     * holder 는 다른 소유자가 Lease 를 쥐고 있어 실행하지 않았을 때만 채운다 (관리자 수동 실행의 409 응답용).
     */
    public record SyncResult(String source, boolean ok, String summary, Holder holder, boolean partial) {
        public SyncResult(String source, boolean ok, String summary) {
            this(source, ok, summary, null, false);
        }

        public SyncResult(String source, boolean ok, String summary, Holder holder) {
            this(source, ok, summary, holder, false);
        }

        public boolean failed() {
            return !ok;
        }

        public boolean busy() {
            return holder != null;
        }

        public static SyncResult skipped(String source, String reason) {
            return new SyncResult(source, true, reason);
        }
    }

    /** Lease 를 잃어 실행을 멈춘다. 본문의 항목 단위 catch 에 잡히지 않게 루프의 try 밖에서 ensureHeld 로 던진다. */
    public static class LeaseLostException extends RuntimeException {
        LeaseLostException(String key) {
            super("Lease 를 잃어 동기화를 중단합니다. key=" + key);
        }
    }

    /**
     * 본문이 항목 일부만 반영하고 끝남. 본문이 끝에서 던지면 실행을 PARTIAL 로 기록한다 (OK 가 아니므로 증분 커서가 전진하지 않는다).
     * 반환값으로는 상태를 실을 수 없어 FAILED 와 같은 방식(끝에서 던지기)을 쓴다.
     */
    public static class PartialException extends RuntimeException {
        public PartialException(String summary) {
            super(summary);
        }
    }

    private record CurrentRun(String source, Long runId) {
    }

    private static final class Held {
        final String key;
        final long token;
        volatile boolean lost;
        volatile ScheduledFuture<?> heartbeat;

        Held(String key, long token) {
            this.key = key;
            this.token = token;
        }
    }

    public SyncResult run(String source, Supplier<String> body) {
        return props.enabled() ? runWithLease(source, body) : runWithCas(source, body);
    }

    // 본문의 항목 하나가 실패했다. 지금 실행에 sync_item_failures 로 남긴다 (실행 밖에서 부르면 로그만)
    public void recordItemFailure(String externalId, RuntimeException error) {
        CurrentRun run = currentRun.get();
        if (run == null) {
            log.warn("[동기화 항목 실패 - 실행 밖] externalId={}", externalId, error);
            return;
        }
        recorder.recordItemFailure(run.runId(), run.source(), externalId, error);
    }

    // 동기화 본문의 항목 처리 전마다 부른다. Heartbeat 가 Lease 상실을 확인했으면 이후 쓰기를 하지 않도록 던진다
    public void ensureHeld() {
        Held lease = held.get();
        if (lease != null && lease.lost) {
            throw new LeaseLostException(lease.key);
        }
    }

    private SyncResult runWithLease(String source, Supplier<String> body) {
        // 1. 획득. 영향 행 0 이면 다른 서버(또는 같은 서버의 다른 트리거)가 실행 중
        String key = JobLeases.syncKey(source);
        Long token = leases.tryAcquire(key, ownerId, props.ttlSeconds()).orElse(null);
        if (token == null) {
            Holder holder = leases.holder(key).orElse(null);
            log.warn("[동기화 건너뜀 - Lease 보유 중] source={}, owner={}, expiresAt={}", source,
                    holder == null ? null : holder.ownerId(), holder == null ? null : holder.expiresAt());
            // 조회 사이에 해제됐으면 holder 가 없다. 그래도 이번 시도는 실행하지 않았으므로 빈 holder 로 busy 를 알린다
            return new SyncResult(source, true, source + " 동기화가 다른 실행에서 진행 중이라 건너뜀",
                    holder != null ? holder : new Holder(null, null));
        }

        // 2. Heartbeat 시작. 영향 행 0 = 상실 -> 표시만 하고 멈춘다 (본문이 ensureHeld 에서 멈춘다)
        Held lease = new Held(key, token);
        lease.heartbeat = heartbeats.scheduleAtFixedRate(() -> heartbeat(source, lease),
                props.heartbeatSeconds(), props.heartbeatSeconds(), TimeUnit.SECONDS);
        held.set(lease);
        try {
            return runExclusively(source, token, lease, body);
        } finally {
            // 3. 해제. owner·token 이 모두 맞을 때만 반영되므로 이미 잃은 Lease 는 건드리지 않는다
            lease.heartbeat.cancel(false);
            held.remove();
            leases.release(key, ownerId, token);
        }
    }

    private void heartbeat(String source, Held lease) {
        try {
            if (!leases.heartbeat(lease.key, ownerId, lease.token, props.ttlSeconds())) {
                lease.lost = true;
                lease.heartbeat.cancel(false);
                log.warn("[동기화 Lease 상실] source={}, owner={}, token={}. 실행을 중단하고 종료 기록을 남기지 않음", source, ownerId,
                        lease.token);
            }
        } catch (RuntimeException e) {
            // DB 일시 장애는 다음 주기에 다시 시도한다. 그사이 만료되면 다음 Heartbeat 가 영향 행 0 으로 상실을 확인한다
            log.warn("[동기화 Lease Heartbeat 실패] source={}, token={}", source, lease.token, e);
        }
    }

    private SyncResult runExclusively(String source, long token, Held lease, Supplier<String> body) {
        Long runId = recorder.start(source, token);
        currentRun.set(new CurrentRun(source, runId));
        try {
            String summary = body.get();
            // 상실을 확인했거나 token 이 이미 바뀌었으면(Heartbeat 가 아직 못 본 경우) 종료 기록이 반영되지 않는다
            if (lease.lost || !recorder.finish(runId, true, summary)) {
                throw new LeaseLostException(lease.key);
            }
            return new SyncResult(source, true, summary);
        } catch (PartialException e) {
            // 성공 종료와 같이 Lease 를 잃었으면 종료 기록을 남기지 않는다
            if (lease.lost || !recorder.partial(runId, e.getMessage())) {
                log.warn("[동기화 중단 - Lease 상실] source={}, runId={}, token={}", source, runId, token);
                operatorAlert.send("[동기화 중단 - Lease 상실] source=" + source + " runId=" + runId);
                return new SyncResult(source, false, LEASE_LOST);
            }
            return partial(source, runId, e);
        } catch (LeaseLostException e) {
            // 이후 쓰기 금지: 종료 기록도 남기지 않는다. run 은 다음 획득자의 start 가 LEASE_EXPIRED 로 닫는다
            log.warn("[동기화 중단 - Lease 상실] source={}, runId={}, token={}", source, runId, token);
            operatorAlert.send("[동기화 중단 - Lease 상실] source=" + source + " runId=" + runId);
            return new SyncResult(source, false, LEASE_LOST);
        } catch (RuntimeException e) {
            return fail(source, runId, e);
        } finally {
            currentRun.remove();
        }
    }

    private SyncResult runWithCas(String source, Supplier<String> body) {
        String current = running.compareAndExchange(null, source);
        if (current != null) {
            log.warn("[동기화 건너뜀 - 실행 중] source={}, running={}", source, current);
            return SyncResult.skipped(source, current + " 동기화가 실행 중이라 건너뜀");
        }
        try {
            Long runId = recorder.start(source);
            currentRun.set(new CurrentRun(source, runId));
            try {
                String summary = body.get();
                recorder.finish(runId, true, summary);
                return new SyncResult(source, true, summary);
            } catch (PartialException e) {
                recorder.partial(runId, e.getMessage());
                return partial(source, runId, e);
            } catch (RuntimeException e) {
                return fail(source, runId, e);
            } finally {
                currentRun.remove();
            }
        } finally {
            running.set(null);
        }
    }

    private SyncResult partial(String source, Long runId, PartialException e) {
        String summary = mask(e.getMessage());
        log.warn("[동기화 일부 실패] source={}, runId={}, summary={}", source, runId, summary);
        operatorAlert.send("[동기화 일부 실패] source=" + source + " " + summary);
        return new SyncResult(source, true, summary, null, true);
    }

    private SyncResult fail(String source, Long runId, RuntimeException e) {
        String summary = "실패: " + mask(e.getClass().getSimpleName() + ": " + e.getMessage());
        log.error("[동기화 실패] source={}, runId={}", source, runId, e);
        recorder.finish(runId, false, summary);
        operatorAlert.send("[동기화 실패] source=" + source + " " + summary);
        return new SyncResult(source, false, summary);
    }

    @PreDestroy
    void shutdown() {
        heartbeats.shutdownNow();
    }

    static String mask(String text) {
        return text == null ? "" : SECRET_PARAM.matcher(text).replaceAll("$1=***");
    }

    private static String hostname() {
        try {
            String name = InetAddress.getLocalHost().getHostName();
            return name.length() > 50 ? name.substring(0, 50) : name;
        } catch (UnknownHostException e) {
            return "unknown-host";
        }
    }
}
