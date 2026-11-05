package com.stagelog.ops.application;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Queue·Outbox 설정 (jan.queue).
 * relay.enabled: Outbox Relay 실행 여부(비우면 켜진 Consumer 가 있을 때). threads: Queue 마다 같은 프로세스에서 도는 polling thread 수(0 이면 Consumer 없음).
 * waitSeconds: SQS Long Polling 대기. visibilitySeconds: Queue 별 Visibility Timeout(처리 중 30초마다 이 값으로 연장).
 * backoffSeconds: 일시 실패 n 번째(ApproximateReceiveCount = n)의 재전달 대기, 5번째 실패는 DLQ 로 보낸다.
 * autoCreate: 기동 시 Queue·DLQ 생성 (로컬·테스트). 운영은 인프라에서 만들고 false.
 */
@ConfigurationProperties(prefix = "jan.queue")
public record QueueProperties(Relay relay, Consumer consumer, Map<String, Integer> visibilitySeconds, List<Integer> backoffSeconds,
                              boolean autoCreate) {

    public static final int MAX_RECEIVE_COUNT = 5;

    // enabled 를 비워 두면 이 프로세스가 Queue 를 하나라도 소비할 때 Relay 도 돈다 (Outbox 에 쓰는 기능 = 그 Queue 를 소비하는 기능)
    public record Relay(Boolean enabled) {
    }

    public boolean relayEnabled(boolean anyConsumer) {
        return relay == null || relay.enabled() == null ? anyConsumer : relay.enabled();
    }

    public record Consumer(int threads, int waitSeconds) {
    }

    public int visibilityOf(String queue) {
        Integer seconds = visibilitySeconds.get(queue);
        if (seconds == null) {
            throw new IllegalStateException("jan.queue.visibility-seconds 에 Queue 설정이 없습니다. queue=" + queue);
        }
        return seconds;
    }

    // 수신 횟수 n(1부터) 실패 뒤의 재전달 대기. 표 끝을 넘으면 마지막 값
    public int backoffAfter(int receiveCount) {
        return backoffSeconds.get(Math.min(Math.max(receiveCount, 1), backoffSeconds.size()) - 1);
    }

    public static String deadLetterQueue(String queue) {
        return queue + "-dlq";
    }
}
