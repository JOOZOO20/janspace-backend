package com.stagelog.event.application.sync;

import com.stagelog.ops.application.OperatorAlert;
import com.stagelog.ops.application.SyncRunRecorder;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 동기화 실행 틀: sync_runs 시작 -> 본문 -> OK/FAILED 종료. 본문 예외는 밖으로 내지 않고 FAILED 기록 + 운영자 알림으로 바꾼다
 * (스케줄러·관리자 트리거가 같은 틀을 탄다). 요약의 URL 에 섞인 키 값은 가린다(Spring 7 의 RestClient 예외 메시지는 쿼리스트링을
 * 이미 잘라내므로, 다른 경로로 들어오는 메시지에 대한 대비다).
 * 동기화는 한 번에 하나만 돈다: 스케줄 실행 중 관리자 수동 실행이 겹치면 같은 행사를 두 트랜잭션이 접어
 * DB 제약이 없는 "(event, field) 당 PENDING 1건" 규칙이 깨질 수 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncRunner {

    private static final java.util.regex.Pattern SECRET_PARAM =
            java.util.regex.Pattern.compile("(?i)(apikey|service|publishedKey|x-api-key)=[^&\\s\"']+");

    private final SyncRunRecorder recorder;
    private final OperatorAlert operatorAlert;
    // ponytail: JVM 안에서만 막는다. 인스턴스가 둘 이상이 되면 sync_runs 조건부 선점이나 분산 락으로 바꾼다
    private final AtomicReference<String> running = new AtomicReference<>();

    /** 실행 결과. source 는 sync_runs.source 이름(제공자 이름), summary 는 사람이 읽는 한 줄 요약. */
    public record SyncResult(String source, boolean ok, String summary) {
        public boolean failed() {
            return !ok;
        }

        public static SyncResult skipped(String source, String reason) {
            return new SyncResult(source, true, reason);
        }
    }

    public SyncResult run(String source, Supplier<String> body) {
        String current = running.compareAndExchange(null, source);
        if (current != null) {
            log.warn("[동기화 건너뜀 - 실행 중] source={}, running={}", source, current);
            return SyncResult.skipped(source, current + " 동기화가 실행 중이라 건너뜀");
        }
        try {
            return runExclusively(source, body);
        } finally {
            running.set(null);
        }
    }

    private SyncResult runExclusively(String source, Supplier<String> body) {
        Long runId = recorder.start(source);
        try {
            String summary = body.get();
            recorder.finish(runId, true, summary);
            return new SyncResult(source, true, summary);
        } catch (RuntimeException e) {
            String summary = "실패: " + mask(e.getClass().getSimpleName() + ": " + e.getMessage());
            log.error("[동기화 실패] source={}, runId={}", source, runId, e);
            recorder.finish(runId, false, summary);
            operatorAlert.send("[동기화 실패] source=" + source + " " + summary);
            return new SyncResult(source, false, summary);
        }
    }

    static String mask(String text) {
        return text == null ? "" : SECRET_PARAM.matcher(text).replaceAll("$1=***");
    }
}
