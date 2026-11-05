package com.stagelog.media.application;

import com.stagelog.media.application.ImageSet.Variant;
import com.stagelog.media.application.port.MediaStoragePort;
import com.stagelog.media.domain.MediaRenditionRepository;
import com.stagelog.media.domain.RenditionProfile;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 서빙 공개 계약. 이미지를 돌려주는 응답이 기존 URL 필드 옆에 둘 images 를 만든다. 목록은 소유자 여러 개를 쿼리 한 번으로 묶는다.
 * 소유자의 현재 원본 key 로 등록된 asset 의 READY rendition 만 쓰고, 나머지 자리는 원본 URL 이다.
 */
@Service
@RequiredArgsConstructor
public class MediaImages {

    private final MediaRenditionRepository renditions;
    private final MediaStoragePort storage;

    /** 소유자의 현재 원본. key 는 저장 key(제공자 원본 URL 이면 그 URL), url 은 기존 응답 필드의 원본 URL. */
    public record Original(long ownerId, String key, String url) {
    }

    // 원본이 없으면(url null) 결과에 넣지 않는다
    @Transactional(readOnly = true)
    public Map<Long, ImageSet> of(MediaOwnerType ownerType, Collection<Original> originals) {
        List<Original> present = originals.stream().filter(o -> o.key() != null && o.url() != null).toList();
        if (present.isEmpty()) {
            return Map.of();
        }
        // 1. 소유자의 현재 key 로 등록된 asset 중 가장 최근 것의 READY rendition 을 (소유자, profile, format) 으로 모은다.
        //    고정 key 덮어쓰기(버전 key 꺼짐)면 같은 key 에 옛 asset 이 남아 있으므로 최신 asset 만 쓴다(그동안은 원본 URL)
        Map<Long, String> currentKey = new HashMap<>();
        present.forEach(o -> currentKey.put(o.ownerId(), o.key()));
        List<MediaRenditionRepository.Ready> rows = renditions.findReady(ownerType.name(), currentKey.keySet(),
                present.stream().map(Original::key).distinct().toList()).stream()
                .filter(r -> r.getOriginalKey().equals(currentKey.get(r.getOwnerId()))).toList();
        Map<Long, Long> latestAsset = new HashMap<>();
        rows.forEach(r -> latestAsset.merge(r.getOwnerId(), r.getAssetId(), Math::max));
        Map<String, String> ready = new HashMap<>();
        for (MediaRenditionRepository.Ready r : rows) {
            if (r.getAssetId().equals(latestAsset.get(r.getOwnerId())) && "READY".equals(r.getStatus())) {
                ready.put(r.getOwnerId() + "/" + r.getProfile() + "/" + r.getFormat(), storage.publicUrl(r.getOutputKey()));
            }
        }
        // 2. 자리마다 READY 면 rendition URL, 아니면 원본 URL. 두 번째 형식은 JPEG 또는 투명 원본의 PNG 중 있는 것
        Map<Long, ImageSet> result = new HashMap<>();
        for (Original o : present) {
            result.put(o.ownerId(), new ImageSet(variant(ready, o, RenditionProfile.THUMB), variant(ready, o, RenditionProfile.LIST),
                    variant(ready, o, RenditionProfile.DETAIL)));
        }
        return result;
    }

    public ImageSet of(MediaOwnerType ownerType, Original original) {
        return of(ownerType, List.of(original)).get(original.ownerId());
    }

    private static Variant variant(Map<String, String> ready, Original o, RenditionProfile profile) {
        String prefix = o.ownerId() + "/" + profile.key() + "/";
        String webp = ready.getOrDefault(prefix + "webp", o.url());
        String fallback = Objects.requireNonNullElse(ready.get(prefix + "jpeg"), ready.getOrDefault(prefix + "png", o.url()));
        return new Variant(webp, fallback);
    }
}
