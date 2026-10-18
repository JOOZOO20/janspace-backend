package com.janspace.photocard.domain;

import com.janspace.common.entity.SoftDeletableBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 유저 사진 + 스티커 배치 한 장. user_id·attendance_id 는 다른 기능의 엔티티라 ID 로만 든다.
 * 기록이 삭제되면 연결만 끊고(unlink), 탈퇴·삭제는 소프트 삭제다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "photocards")
public class Photocard extends SoftDeletableBaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "attendance_id")
    private Long attendanceId;

    @Column(name = "image_key", nullable = false, length = 255)
    private String imageKey;

    @Convert(converter = PlacedStickersConverter.class)
    @Column(nullable = false, columnDefinition = "json")
    private List<PlacedSticker> stickers = List.of();

    public Photocard(Long userId, Long attendanceId, String imageKey, List<PlacedSticker> stickers) {
        this.userId = Objects.requireNonNull(userId);
        this.attendanceId = attendanceId;
        this.imageKey = Objects.requireNonNull(imageKey);
        this.stickers = stickers == null ? List.of() : List.copyOf(stickers);
    }

    // 기록이 삭제됐다: 포토카드는 바인더에 남고 별 연결만 끊는다
    public void unlink() {
        this.attendanceId = null;
    }

    public void softDelete() {
        super.delete(Instant.now());
    }
}
