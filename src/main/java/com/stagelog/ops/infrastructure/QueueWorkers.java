package com.stagelog.ops.infrastructure;

import com.stagelog.ops.application.OutboxRelay;
import com.stagelog.ops.application.QueueConsumer;
import com.stagelog.ops.application.QueueHandler;
import com.stagelog.ops.application.QueueProperties;
import com.stagelog.ops.application.port.MessageQueuePort;
import com.stagelog.ops.application.port.MessageQueuePort.Received;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Outbox Relay 와 Queue Consumer 의 실행 위치: 같은 애플리케이션 프로세스 안의 전용 스레드다 (진입 어댑터).
 * 기본 @Scheduled 스레드는 하나라 KOPIS 동기화 같은 긴 잡이 점유하므로, Relay(1초 간격, 스레드 1개)와 Queue 마다 polling thread
 * (Handler 의 threads(), 0 이면 jan.queue.consumer.threads 개)를 따로 둔다. 각 polling thread 는 메시지를 1건씩 받아 처리한다(받아 두고 기다리는
 * 메시지의 Visibility Timeout 이 처리 중에 지나지 않게). 낮은 우선순위 Queue 가 있는 Handler 는 같은 thread 가 두 Queue 를 비율대로 받는다.
 * enabled() 가 false 인 Handler 의 Queue 는 polling 하지 않는다. Relay 는 jan.queue.relay.enabled, 비어 있으면 켜진 Handler 가 있을 때 돈다.
 * jan.queue.auto-create 면 기동 시 켜진 Handler 의 Queue 와 DLQ(이름 + "-dlq", maxReceiveCount 5)를 만든다 (로컬·테스트).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueueWorkers implements SmartLifecycle {

    static final long RELAY_INTERVAL_MILLIS = 1000;
    // 받기 실패(SQS 장애) 뒤 다시 받기까지
    static final long POLL_ERROR_PAUSE_MILLIS = 1000;

    private final List<QueueHandler> handlers;
    private final OutboxRelay relay;
    private final QueueConsumer consumer;
    private final MessageQueuePort queue;
    private final QueueProperties props;

    private volatile boolean running;
    private ScheduledExecutorService relayTimer;
    private ExecutorService pollers;

    @Override
    public void start() {
        running = true;
        List<QueueHandler> active = handlers.stream().filter(QueueHandler::enabled).toList();
        if (props.autoCreate()) {
            for (QueueHandler handler : active) {
                for (String name : queuesOf(handler)) {
                    queue.ensureQueue(name, QueueProperties.deadLetterQueue(name), props.visibilityOf(name), QueueProperties.MAX_RECEIVE_COUNT);
                    log.info("[Queue 생성] queue={}, dlq={}, visibilitySeconds={}", name, QueueProperties.deadLetterQueue(name),
                            props.visibilityOf(name));
                }
            }
        }
        if (props.relayEnabled(!active.isEmpty())) {
            relayTimer = Executors.newSingleThreadScheduledExecutor(daemon("outbox-relay"));
            relayTimer.scheduleWithFixedDelay(this::relayOnce, RELAY_INTERVAL_MILLIS, RELAY_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
        }
        if (props.consumer().threads() > 0 && !active.isEmpty()) {
            int total = active.stream().mapToInt(this::threadsOf).sum();
            pollers = Executors.newFixedThreadPool(Math.max(total, 1), daemon("queue-consumer"));
            List<String> names = new ArrayList<>();
            for (QueueHandler handler : active) {
                for (int i = 0; i < threadsOf(handler); i++) {
                    pollers.submit(() -> {
                        if (handler.lowPriorityQueue() == null) {
                            poll(handler);
                        } else {
                            pollWithPriority(handler);
                        }
                    });
                }
                names.add(handler.queue() + (handler.lowPriorityQueue() == null ? "" : "+" + handler.lowPriorityQueue()) + "x" + threadsOf(handler));
            }
            log.info("[Queue Consumer 시작] queues={}", names);
        }
    }

    private int threadsOf(QueueHandler handler) {
        return handler.threads() > 0 ? handler.threads() : props.consumer().threads();
    }

    private static List<String> queuesOf(QueueHandler handler) {
        return handler.lowPriorityQueue() == null ? List.of(handler.queue()) : List.of(handler.queue(), handler.lowPriorityQueue());
    }

    private void relayOnce() {
        try {
            relay.relayOnce();
        } catch (RuntimeException e) {
            // DB 장애 등. 다음 주기에 다시 한다
            log.warn("[Outbox Relay 실패]", e);
        }
    }

    private void poll(QueueHandler handler) {
        while (running) {
            try {
                for (Received message : queue.receive(handler.queue(), 1, props.consumer().waitSeconds(), null)) {
                    consumer.process(handler, message);
                }
            } catch (RuntimeException e) {
                if (!running) {
                    return;
                }
                log.warn("[Queue 수신·처리 오류] queue={}", handler.queue(), e);
                pause();
            } catch (Error e) {
                // 처리 중 비정상 종료와 같다: 메시지는 삭제·연장 없이 남아 Visibility Timeout 뒤 다시 전달된다
                log.error("[Queue 처리 중단] 메시지를 놓고 계속 받습니다. queue={}", handler.queue(), e);
            }
        }
    }

    /**
     * 우선 Queue 와 낮은 우선순위 Queue 를 한 thread 가 번갈아 받는다. 우선 Queue 를 priorityRatio 번 받을 때마다 낮은 쪽을 1번 받고,
     * 우선 Queue 가 비면 낮은 쪽을 연속으로 받는다. 둘 다 비면 우선 Queue 를 Long Polling 으로 기다린다(새 원본이 오면 바로 받도록).
     * Queue 마다 thread 를 따로 두면 thread 수만큼 백필이 신규와 같은 몫을 가져가므로, 비율은 같은 thread 안의 수신 순서로 정한다.
     * ponytail: 비었는지는 대기 0초 수신(Short Polling)으로 본다. SQS Short Polling 은 서버 일부만 보므로 메시지가 있어도 빈 응답이 올 수 있고,
     *           그때는 그 한 번만 낮은 쪽을 받는다. 비율이 정확해야 하면 우선 Queue 의 ApproximateNumberOfMessages 로 판단한다
     */
    private void pollWithPriority(QueueHandler handler) {
        String high = handler.queue();
        String low = handler.lowPriorityQueue();
        int sinceLow = 0;
        while (running) {
            try {
                if (sinceLow >= handler.priorityRatio()) {
                    sinceLow = 0;
                    if (receiveOne(handler, low, 0)) {
                        continue;
                    }
                }
                if (receiveOne(handler, high, 0)) {
                    sinceLow++;
                } else if (!receiveOne(handler, low, 0) && receiveOne(handler, high, props.consumer().waitSeconds())) {
                    sinceLow++;
                }
            } catch (RuntimeException e) {
                if (!running) {
                    return;
                }
                log.warn("[Queue 수신·처리 오류] queue={}+{}", high, low, e);
                pause();
            } catch (Error e) {
                log.error("[Queue 처리 중단] 메시지를 놓고 계속 받습니다. queue={}+{}", high, low, e);
            }
        }
    }

    // 1건 받아 처리했으면 true
    private boolean receiveOne(QueueHandler handler, String name, int waitSeconds) {
        List<Received> messages = queue.receive(name, 1, waitSeconds, null);
        for (Received message : messages) {
            consumer.process(handler, name, message);
        }
        return !messages.isEmpty();
    }

    private void pause() {
        try {
            Thread.sleep(POLL_ERROR_PAUSE_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void stop() {
        running = false;
        if (relayTimer != null) {
            relayTimer.shutdownNow();
        }
        if (pollers != null) {
            pollers.shutdown();
            try {
                // 처리 중인 메시지는 끝까지 처리하게 Long Polling 대기만큼 기다린다. 못 끝낸 메시지는 Visibility Timeout 뒤 다시 전달된다
                if (!pollers.awaitTermination(props.consumer().waitSeconds() + 5L, TimeUnit.SECONDS)) {
                    pollers.shutdownNow();
                }
            } catch (InterruptedException e) {
                pollers.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }
}
