package com.stagelog.photocard.application;

import com.stagelog.media.application.ImageSet;
import com.stagelog.media.application.MediaImages;
import com.stagelog.media.application.MediaOwnerType;
import com.stagelog.photocard.application.port.ImageStoragePort;
import com.stagelog.photocard.domain.Photocard;
import com.stagelog.photocard.domain.PhotocardKind;
import com.stagelog.photocard.domain.PlacedSticker;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 프론트 Photocard 계약과 같은 모양의 읽기 스냅샷. imageUrl 은 저장 키를 CDN 공개 URL 로 바꾼 것.
 */
public record PhotocardView(Long id, PhotocardKind kind, String imageUrl, ImageSet images, String backgroundColor, Long frameId,
                            List<PlacedSticker> stickers, Long linkedRecordId, Instant createdAt) {

    static PhotocardView of(Photocard card, ImageStoragePort images, MediaImages mediaImages) {
        return all(List.of(card), images, mediaImages).get(0);
    }

    // 카드·네컷 images 를 종류별 쿼리 한 번으로 묶는다
    static List<PhotocardView> all(List<Photocard> cards, ImageStoragePort images, MediaImages mediaImages) {
        Map<Long, ImageSet> sets = new HashMap<>();
        for (PhotocardKind kind : PhotocardKind.values()) {
            sets.putAll(mediaImages.of(ownerType(kind), cards.stream().filter(c -> c.getKind() == kind)
                    .map(c -> new MediaImages.Original(c.getId(), c.getImageKey(), images.publicUrl(c.getImageKey()))).toList()));
        }
        return cards.stream().map(card -> new PhotocardView(card.getId(), card.getKind(), images.publicUrl(card.getImageKey()),
                sets.get(card.getId()), card.getBackgroundColor(), card.getFrameId(), card.getStickers(), card.getAttendanceId(),
                card.getCreatedAt())).toList();
    }

    static MediaOwnerType ownerType(PhotocardKind kind) {
        return kind == PhotocardKind.FOURCUT ? MediaOwnerType.FOURCUT : MediaOwnerType.PHOTOCARD;
    }
}
