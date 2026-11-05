package com.stagelog.ops.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.stagelog.ops.application.OutboxRelay;
import com.stagelog.ops.application.QueueConsumer;
import com.stagelog.ops.application.QueueHandler;
import com.stagelog.ops.application.QueueProperties;
import com.stagelog.ops.application.port.MessageQueuePort;
import com.stagelog.ops.domain.QueueMessageFailureRepository;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QueueWorkersPriorityTest {

    @Test
    @DisplayName("낮은 우선순위 Queue 가 있으면 우선 Queue 4회 수신마다 낮은 쪽 1회, 우선 Queue 가 비면 낮은 쪽을 연속으로 받는다")
    void high_four_then_low_one_and_low_continuously_when_high_empty() throws InterruptedException {
        FakeQueue queue = new FakeQueue();
        for (int i = 0; i < 8; i++) {
            queue.add("high", "H" + i);
        }
        for (int i = 0; i < 4; i++) {
            queue.add("low", "L" + i);
        }
        List<String> handled = new CopyOnWriteArrayList<>();
        QueueHandler handler = new QueueHandler() {
            public String queue() {
                return "high";
            }

            public boolean enabled() {
                return true;
            }

            public String lowPriorityQueue() {
                return "low";
            }

            public int threads() {
                return 1;
            }

            public void handle(String body) {
                handled.add(body);
            }
        };
        QueueProperties props = new QueueProperties(new QueueProperties.Relay(false), new QueueProperties.Consumer(5, 0),
                Map.of("high", 30, "low", 30), List.of(1, 1, 1, 1), false);
        QueueWorkers workers = new QueueWorkers(List.of(handler), mock(OutboxRelay.class),
                new QueueConsumer(queue, mock(QueueMessageFailureRepository.class), props), queue, props);
        workers.start();
        try {
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (handled.size() < 12 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
        } finally {
            workers.stop();
        }
        // handler.threads()=1 이 jan.queue.consumer.threads(5)보다 우선해 한 thread 가 순서대로 받는다
        assertThat(handled).containsExactly("H0", "H1", "H2", "H3", "L0", "H4", "H5", "H6", "H7", "L1", "L2", "L3");
        assertThat(queue.deleted).containsOnlyKeys("high", "low");
        assertThat(queue.deleted.get("low")).hasSize(4);
    }

    static final class FakeQueue implements MessageQueuePort {

        final Map<String, Deque<String>> queues = new ConcurrentHashMap<>();
        final Map<String, List<String>> deleted = new ConcurrentHashMap<>();

        void add(String queue, String body) {
            queues.computeIfAbsent(queue, q -> new ArrayDeque<>()).add(body);
        }

        @Override
        public void send(String queue, String body) {
            add(queue, body);
        }

        @Override
        public synchronized List<Received> receive(String queue, int max, int waitSeconds, Integer visibilitySeconds) {
            String body = queues.getOrDefault(queue, new ArrayDeque<>()).poll();
            return body == null ? Collections.emptyList() : List.of(new Received(body, queue + ":" + body, body, 1));
        }

        @Override
        public void changeVisibility(String queue, String receiptHandle, int seconds) {
        }

        @Override
        public void delete(String queue, String receiptHandle) {
            deleted.computeIfAbsent(queue, q -> new CopyOnWriteArrayList<>()).add(receiptHandle);
        }

        @Override
        public void ensureQueue(String queue, String deadLetterQueue, int visibilityTimeoutSeconds, int maxReceiveCount) {
        }
    }
}
