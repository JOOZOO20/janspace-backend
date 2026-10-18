package com.janspace.photocard.application;

import com.janspace.photocard.application.port.ImageStoragePort;
import com.janspace.photocard.domain.PlacedSticker;
import com.janspace.photocard.domain.Photocard;
import java.time.Instant;
import java.util.List;

/**
 * 프론트 Photocard 계약과 같은 모양의 읽기 스냅샷. imageUrl 은 저장 키를 CDN 공개 URL 로 바꾼 것.
 */
public record PhotocardView(Long id, String imageUrl, List<PlacedSticker> stickers, Long linkedRecordId, Instant createdAt) {

    static PhotocardView of(Photocard card, ImageStoragePort images) {
        return new PhotocardView(card.getId(), images.publicUrl(card.getImageKey()), card.getStickers(), card.getAttendanceId(),
                card.getCreatedAt());
    }
}
