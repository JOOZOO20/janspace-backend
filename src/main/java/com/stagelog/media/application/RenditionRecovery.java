package com.stagelog.media.application;

import com.stagelog.media.domain.MediaAssetRepository;
import com.stagelog.media.domain.MediaRenditionRepository;
import com.stagelog.ops.application.OperatorAlert;
import com.stagelog.ops.application.Outbox;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Zombie rendition 회수 (1분마다, media/infrastructure 의 스케줄러). Worker 가 처리 중에 죽어 PROCESSING 인 채 Lease 가 만료된 행을
 * PENDING 으로 돌리고 변환 메시지를 다시 발행한다(원래 메시지는 이미 삭제됐거나 DLQ 로 갔을 수 있다). 시도 횟수를 다 쓴 행은 FAILED + 운영자 알림.
 * 조건부 UPDATE 라 여러 서버가 같이 돌아도 한 행은 한 서버만 회수하고, 회수와 재발행은 한 트랜잭션이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RenditionRecovery {

    static final int BATCH = 100;

    private final MediaRenditionRepository renditions;
    private final MediaAssetRepository assets;
    private final Outbox outbox;
    private final OperatorAlert alert;
    private final TransactionTemplate transactions;

    // 회수(PENDING + 재발행)한 행 수
    public int recover() {
        List<Long> failed = new ArrayList<>();
        Integer recovered = transactions.execute(s -> {
            int n = 0;
            for (MediaRenditionRepository.Expired expired : renditions.findExpired(BATCH)) {
                // Worker 의 완료·실패와 같은 순서(asset -> rendition)로 잠근다
                assets.lockById(expired.getAssetId());
                if (renditions.failExpired(expired.getId(), MediaTranscodeHandler.MAX_ATTEMPTS, "ZOMBIE: 시도 횟수 초과") == 1) {
                    assets.aggregate(expired.getAssetId());
                    failed.add(expired.getId());
                } else if (renditions.resetExpired(expired.getId(), MediaTranscodeHandler.MAX_ATTEMPTS) == 1) {
                    outbox.append(MediaTranscodeHandler.TRANSCODE_QUEUE, new MediaTranscodeHandler.Payload(expired.getId()));
                    n++;
                }
            }
            return n;
        });
        int count = recovered == null ? 0 : recovered;
        if (count > 0 || !failed.isEmpty()) {
            log.warn("[미디어 Zombie 회수] 재발행={}, 시도초과FAILED={}", count, failed);
        }
        if (!failed.isEmpty()) {
            alert.send("[미디어 변환 실패] Lease 만료 후 시도 횟수 초과로 FAILED. renditionIds=" + failed);
        }
        return count;
    }
}
