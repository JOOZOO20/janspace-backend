package com.stagelog.media.infrastructure;

import com.stagelog.media.application.RenditionRetry;
import com.stagelog.ops.application.AuditActor;
import com.stagelog.ops.application.port.AgentActionHandler;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 승인된 MCP 조치 RETRY_MEDIA_RENDITION: FAILED rendition 을 다시 변환한다(RenditionRetry). targetRef 는 rendition ID. 감사 로그는 승인한 관리자 이름이다.
 */
@Component
@RequiredArgsConstructor
public class RenditionRetryActionHandler implements AgentActionHandler {

    private final RenditionRetry retry;

    @Override
    public String type() {
        return "RETRY_MEDIA_RENDITION";
    }

    @Override
    public void execute(String targetRef, Map<String, Object> payload, AuditActor approver) {
        retry.retryFailed(Long.valueOf(targetRef), approver);
    }
}
