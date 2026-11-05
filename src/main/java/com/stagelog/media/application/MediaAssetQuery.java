package com.stagelog.media.application;

import com.stagelog.media.domain.MediaAsset;
import com.stagelog.media.domain.MediaAssetRepository;
import com.stagelog.media.domain.MediaRendition;
import com.stagelog.media.domain.MediaRenditionRepository;
import com.stagelog.ops.application.QueueFailureQuery;
import com.stagelog.ops.application.QueueFailureQuery.Failure;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 진단용 미디어 상태 (MCP get_media_asset_status 와 관리자 화면). 소유자의 asset 전부를 최신순으로, rendition 6개와 각 rendition 변환 메시지의
 * Queue 실패 기록(Outbox 본문의 renditionId 로 연결)을 함께 준다. current 는 asset 원본 key 가 소유자의 현재 원본 key 와 같은지다(서빙은 current 만 쓴다).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MediaAssetQuery {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final List<String> MEDIA_QUEUES = List.of(MediaTranscodeHandler.TRANSCODE_QUEUE, MediaTranscodeHandler.BACKFILL_QUEUE);

    private final MediaAssetRepository assets;
    private final MediaRenditionRepository renditions;
    private final List<MediaOriginals> originals;
    private final QueueFailureQuery queueFailures;

    public record RenditionView(Long id, String profile, String format, String status, int attempt, String leaseOwner,
                                Instant leaseExpiresAt, String lastError, String outputKey, Long bytes, List<Failure> failures) {
    }

    public record AssetView(Long id, String status, String originalKey, String originalSha256, int width, int height, String mimeType,
                            Instant createdAt, Instant updatedAt, boolean current, List<RenditionView> renditions) {
    }

    public List<AssetView> of(MediaOwnerType ownerType, long ownerId) {
        List<MediaAsset> found = assets.findAllByOwnerTypeAndOwnerIdOrderByIdDesc(ownerType.name(), ownerId);
        if (found.isEmpty()) {
            return List.of();
        }
        Optional<String> currentKey = originals.stream().filter(o -> o.supports(ownerType)).findFirst()
                .flatMap(o -> o.currentKey(ownerType, ownerId));
        Map<Long, List<Failure>> failuresByRendition = new HashMap<>();
        for (Failure failure : queueFailures.byQueues(MEDIA_QUEUES)) {
            Long renditionId = renditionIdOf(failure.payload());
            if (renditionId != null) {
                failuresByRendition.computeIfAbsent(renditionId, k -> new ArrayList<>()).add(failure);
            }
        }
        List<AssetView> out = new ArrayList<>();
        for (MediaAsset asset : found) {
            List<RenditionView> views = renditions.findAllByAssetIdOrderById(asset.getId()).stream()
                    .map(r -> view(r, failuresByRendition.getOrDefault(r.getId(), List.of()))).toList();
            out.add(new AssetView(asset.getId(), asset.getStatus().name(), asset.getOriginalKey(), asset.getOriginalSha256(), asset.getWidth(),
                    asset.getHeight(), asset.getMimeType(), asset.getCreatedAt(), asset.getUpdatedAt(),
                    currentKey.map(asset.getOriginalKey()::equals).orElse(false), views));
        }
        return out;
    }

    private static RenditionView view(MediaRendition r, List<Failure> failures) {
        return new RenditionView(r.getId(), r.getProfile(), r.getFormat(), r.getStatus().name(), r.getAttempt(), r.getLeaseOwner(),
                r.getLeaseExpiresAt(), r.getLastError(), r.getOutputKey(), r.getBytes(), List.copyOf(failures));
    }

    private static Long renditionIdOf(String payload) {
        if (payload == null) {
            return null;
        }
        try {
            JsonNode id = MAPPER.readTree(payload).get("renditionId");
            return id == null || !id.isNumber() ? null : id.asLong();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
