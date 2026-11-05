package com.stagelog.ops.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stagelog.ops.application.port.MessageQueuePort;
import com.stagelog.ops.application.port.MessageQueuePort.Received;
import com.stagelog.ops.domain.QueueMessageFailureRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DLQ redrive 의 대상 Queue 판정과 감사 로그. 낮은 우선순위 Queue(JAN-05 jan-media-backfill)도 같은 Handler 의 Queue 라 redrive 와 DLQ_REDRIVE 기록이 된다.
 */
class DeadLettersTest {

    static final String MESSAGE = "6f1c0d1e-8a4b-4f0e-9b7a-2c3d4e5f6a7b";

    MessageQueuePort queue = mock(MessageQueuePort.class);
    AdminAuditRecorder audit = mock(AdminAuditRecorder.class);
    QueueHandler media = new QueueHandler() {
        @Override
        public String queue() {
            return "jan-media-transcode";
        }

        @Override
        public String lowPriorityQueue() {
            return "jan-media-backfill";
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public void handle(String body) {
        }
    };
    DeadLetters deadLetters = new DeadLetters(queue, mock(QueueMessageFailureRepository.class), List.of(media), audit);

    @Test
    @DisplayName("낮은 우선순위 Queue 의 DLQ 메시지를 그 Queue 로 되돌리고 DLQ_REDRIVE 를 남긴다")
    void redrive_low_priority_queue_records_audit() {
        String body = "{\"messageId\":\"" + MESSAGE + "\",\"renditionId\":7}";
        when(queue.receive(eq("jan-media-backfill-dlq"), anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(new Received("sqs-1", "receipt-1", body, 5)));
        AuditActor actor = AuditActor.admin(9L);

        DeadLetters.Redrive result = deadLetters.redrive("jan-media-backfill", List.of(MESSAGE), actor);

        assertThat(result.redriven()).containsExactly(MESSAGE);
        verify(queue).send("jan-media-backfill", body);
        verify(queue).delete("jan-media-backfill-dlq", "receipt-1");
        verify(audit).recordInNewTransaction(eq(actor), eq(AuditAction.DLQ_REDRIVE), eq("DEAD_LETTER_QUEUE"), eq("jan-media-backfill"),
                isNull(), eq(Map.of("redriven", List.of(MESSAGE))), isNull());
    }
}
