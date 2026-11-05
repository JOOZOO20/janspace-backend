package com.stagelog.ops.infrastructure;

import com.stagelog.common.exception.ErrorCode;
import com.stagelog.common.exception.predefined.NotFoundException;
import com.stagelog.ops.application.AuditActor;
import com.stagelog.ops.application.DeadLetters;
import com.stagelog.ops.application.port.AgentActionHandler;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 승인된 MCP 조치 REDRIVE_DEAD_LETTER: 관리자 DLQ redrive 와 같은 경로(DeadLetters.redrive)를 탄다. targetRef 는 메시지 ID, payload.queue 는 원래 Queue.
 * DLQ 에서 메시지를 찾지 못하면(이미 재투입됨 등) 실행 실패로 본다. 감사 로그(DLQ_REDRIVE)는 redrive 가 승인한 관리자 이름으로 남긴다.
 */
@Component
@RequiredArgsConstructor
public class DeadLetterRedriveActionHandler implements AgentActionHandler {

    private final DeadLetters deadLetters;

    @Override
    public String type() {
        return "REDRIVE_DEAD_LETTER";
    }

    @Override
    public void execute(String targetRef, Map<String, Object> payload, AuditActor approver) {
        if (deadLetters.redrive(String.valueOf(payload.get("queue")), List.of(targetRef), approver).redriven().isEmpty()) {
            throw new NotFoundException(ErrorCode.DEAD_LETTER_NOT_FOUND);
        }
    }
}
