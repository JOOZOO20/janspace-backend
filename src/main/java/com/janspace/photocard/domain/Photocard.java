package com.janspace.photocard.domain;

import com.janspace.common.entity.SoftDeletableBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 완성 이미지 한 장 + 그 메타(종류·배경색·프레임·스티커 배치). 네컷도 기기에서 합성한 한 장으로 올라온다.
 * user_id·attendance_id 는 다른 기능의 엔티티라 ID 로만 든다. 기록 하나에는 포토카드 한 장만 붙는다(attendance_id 유일).
 * 기록이 삭제되거나 카드를 지우면 연결을 끊어 그 기록을 다른 카드가 쓸 수 있게 한다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "photocards", uniqueConstraints = @UniqueConstraint(name = "uk_photocards_attendance", columnNames = "attendance_id"))
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PhotocardKind kind;

    // 캔버스 배경색 #RRGGBB. 이 필드가 생기기 전 카드는 없다
    @Column(name = "background_color", length = 7)
    private String backgroundColor;

    // 네컷에 쓴 프레임. 카드는 null, 프레임 없이 만든 네컷도 null
    @Column(name = "frame_id")
    private Long frameId;

    @Convert(converter = PlacedStickersConverter.class)
    @Column(nullable = false, columnDefinition = "json")
    private List<PlacedSticker> stickers = List.of();

    public Photocard(Long userId, Long attendanceId, String imageKey, PhotocardKind kind, String backgroundColor, Long frameId,
                     List<PlacedSticker> stickers) {
        this.userId = Objects.requireNonNull(userId);
        this.attendanceId = attendanceId;
        this.imageKey = Objects.requireNonNull(imageKey);
        this.kind = Objects.requireNonNull(kind);
        this.backgroundColor = backgroundColor;
        this.frameId = frameId;
        this.stickers = stickers == null ? List.of() : List.copyOf(stickers);
    }

    public void linkTo(Long attendanceId) {
        this.attendanceId = Objects.requireNonNull(attendanceId);
    }

    public void unlink() {
        this.attendanceId = null;
    }

    // 삭제된 카드가 기록을 계속 쥐고 있으면 그 기록에 새 카드를 붙일 수 없다
    public void softDelete() {
        unlink();
        super.delete(Instant.now());
    }
}
