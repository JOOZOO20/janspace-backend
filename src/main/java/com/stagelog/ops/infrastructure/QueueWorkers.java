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
 * (jan.queue.consumer.threads 개)를 따로 둔다. 각 polling thread 는 메시지를 1건씩 받아 처리한다(받아 두고 기다리는 메시지의
 * Visibility Timeout 이 처리 중에 지나지 않게). enabled() 가 false 인 Handler 의 Queue 는 polling 하지 않는다.
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
                queue.ensureQueue(handler.queue(), QueueProperties.deadLetterQueue(handler.queue()), props.visibilityOf(handler.queue()),
                        QueueProperties.MAX_RECEIVE_COUNT);
                log.info("[Queue 생성] queue={}, dlq={}, visibilitySeconds={}", handler.queue(),
                        QueueProperties.deadLetterQueue(handler.queue()), props.visibilityOf(handler.queue()));
            }
        }
        if (props.relay().enabled()) {
            relayTimer = Executors.newSingleThreadScheduledExecutor(daemon("outbox-relay"));
            relayTimer.scheduleWithFixedDelay(this::relayOnce, RELAY_INTERVAL_MILLIS, RELAY_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
        }
        int threads = props.consumer().threads();
        if (threads > 0 && !active.isEmpty()) {
            pollers = Executors.newFixedThreadPool(threads * active.size(), daemon("queue-consumer"));
            List<String> names = new ArrayList<>();
            for (QueueHandler handler : active) {
                for (int i = 0; i < threads; i++) {
                    pollers.submit(() -> poll(handler));
                }
                names.add(handler.queue());
            }
            log.info("[Queue Consumer 시작] queues={}, threadsPerQueue={}", names, threads);
        }
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
