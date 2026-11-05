package com.stagelog.ops.application;

/**
 * Queue 메시지 처리 계약. 메시지를 처리하는 기능(event 의 병합·포스터 입수, JAN-05 media 변환)이 구현하고, ops 의 Consumer 가 수신·Visibility 연장·
 * 재시도 backoff·DLQ·실패 기록을 맡는다. ops 가 기능 패키지를 참조하지 않게 하는 역방향 계약이다.
 * Consumer 는 같은 메시지를 두 번 이상 줄 수 있다(At-least-once). handle 은 다시 실행해도 결과가 같아야 한다.
 */
public interface QueueHandler {

    // 받는 Queue 이름. DLQ 는 이름 + "-dlq"
    String queue();

    // false 면 이 Queue 의 Consumer 를 띄우지 않는다 (기능 플래그)
    boolean enabled();

    // 같은 handle 로 처리하는 낮은 우선순위 Queue (JAN-05 백필). null 이면 없다. 있으면 polling thread 가 두 Queue 를 priorityRatio 비율로 받는다
    default String lowPriorityQueue() {
        return null;
    }

    // 우선 Queue 를 이 횟수만큼 받을 때마다 낮은 우선순위 Queue 를 1번 받는다. 우선 Queue 가 비면 낮은 우선순위 Queue 를 계속 받는다
    default int priorityRatio() {
        return 4;
    }

    // 이 Handler 의 polling thread 수. 0 이면 jan.queue.consumer.threads
    default int threads() {
        return 0;
    }

    /**
     * 메시지 본문(JSON) 처리. 정상 반환 = 삭제. PermanentMessageFailure = 재시도 없이 실패 기록 후 삭제.
     * 그 밖의 RuntimeException = 일시 실패로 보고 수신 횟수에 따라 늦춰 다시 받는다 (5회 실패하면 DLQ).
     */
    void handle(String body);
}
