package com.stagelog.media.application;

import com.stagelog.common.exception.ErrorCode;
import com.stagelog.common.exception.predefined.ConflictException;
import com.stagelog.common.exception.predefined.NotFoundException;
import com.stagelog.media.domain.MediaAssetRepository;
import com.stagelog.media.domain.MediaRendition;
import com.stagelog.media.domain.MediaRenditionRepository;
import com.stagelog.ops.application.AdminAuditRecorder;
import com.stagelog.ops.application.AuditAction;
import com.stagelog.ops.application.AuditActor;
import com.stagelog.ops.application.Outbox;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실패한 rendition 재변환 (MCP 조치 RETRY_MEDIA_RENDITION 승인 경로). FAILED 를 PENDING 으로 돌리고 시도 횟수를 새로 센 뒤
 * 변환 메시지를 jan-media-transcode 에 다시 넣는다. 상태 변경·Outbox 메시지·감사 로그가 한 트랜잭션이라 롤백되면 셋 다 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RenditionRetry {

    private final MediaRenditionRepository renditions;
    private final MediaAssetRepository assets;
    private final Outbox outbox;
    private final AdminAuditRecorder audit;

    @Transactional
    public void retryFailed(Long renditionId, AuditActor actor) {
        MediaRendition rendition = renditions.findById(renditionId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.MEDIA_RENDITION_NOT_FOUND));
        // 집계를 Worker 의 완료·실패와 같은 asset 단위로 줄 세운다
        assets.lockById(rendition.getAssetId());
        if (renditions.resetFailed(renditionId) == 0) {
            throw new ConflictException(ErrorCode.MEDIA_RENDITION_NOT_FAILED);
        }
        assets.aggregate(rendition.getAssetId());
        String messageId = outbox.append(MediaTranscodeHandler.TRANSCODE_QUEUE, new MediaTranscodeHandler.Payload(renditionId));
        audit.record(actor, AuditAction.MEDIA_RENDITION_RETRY, "MEDIA_RENDITION", String.valueOf(renditionId),
                Map.of("status", "FAILED"), Map.of("status", "PENDING", "messageId", messageId), null);
        log.info("[미디어 rendition 재변환 요청] assetId={}, renditionId={}, messageId={}", rendition.getAssetId(), renditionId, messageId);
    }
}
