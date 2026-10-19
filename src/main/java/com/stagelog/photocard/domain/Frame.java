package com.stagelog.photocard.domain;

import com.stagelog.common.entity.SoftDeletableBaseEntity;
import com.stagelog.common.exception.ErrorCode;
import com.stagelog.common.exception.predefined.BadRequestException;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 네컷 프레임 하나: SVG(사진 칸은 투명) + 사진 칸 좌표. 합성은 기기에서 하므로 서버는 파일과 칸 좌표를 내려주기만 한다.
 * 삭제는 소프트 삭제다. 이미 만든 네컷의 frame_id 가 가리키는 행이 남아야 하고, 쓰인 프레임도 내릴 수 있어야 하기 때문이다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "frames")
public class Frame extends SoftDeletableBaseEntity {

    public static final int CANVAS_WIDTH = 1080;
    public static final int CANVAS_HEIGHT = 3240;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FrameKind kind;

    @Column(length = 50)
    private String season;

    @Column(name = "available_from")
    private LocalDate availableFrom;

    @Column(name = "available_until")
    private LocalDate availableUntil;

    @Column(name = "image_key", nullable = false, length = 255)
    private String imageKey;

    @Convert(converter = SlotRectsConverter.class)
    @Column(name = "slot_rects", nullable = false, columnDefinition = "json")
    private List<SlotRect> slotRects = List.of();

    public Frame(String name, FrameKind kind, String season, LocalDate availableFrom, LocalDate availableUntil, String imageKey,
                 List<SlotRect> slotRects) {
        update(name, kind, season, availableFrom, availableUntil);
        replaceFile(imageKey, slotRects);
    }

    // 상시 프레임은 기간을 갖지 않고, 스페셜은 시작·종료일이 모두 있어야 한다
    public void update(String name, FrameKind kind, String season, LocalDate availableFrom, LocalDate availableUntil) {
        if (kind == FrameKind.ORIGINAL && (availableFrom != null || availableUntil != null)) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT, "상시(ORIGINAL) 프레임에는 배포 기간을 넣지 않습니다.");
        }
        if (kind == FrameKind.SPECIAL && (availableFrom == null || availableUntil == null || availableFrom.isAfter(availableUntil))) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT, "스페셜(SPECIAL) 프레임은 availableFrom <= availableTo 인 배포 기간이 필요합니다.");
        }
        this.name = name;
        this.kind = kind;
        this.season = season;
        this.availableFrom = availableFrom;
        this.availableUntil = availableUntil;
    }

    // SVG 와 칸 좌표는 한 디자인이라(칸 = SVG 의 투명 구멍) 항상 함께 바뀐다
    public void replaceFile(String imageKey, List<SlotRect> slotRects) {
        validateSlots(slotRects);
        this.imageKey = imageKey;
        this.slotRects = List.copyOf(slotRects);
    }

    // 사진 칸 3개(응원석 변형) 또는 4개, 모두 1080×3240 캔버스 안
    public static void validateSlots(List<SlotRect> slots) {
        if (slots == null || slots.size() < 3 || slots.size() > 4) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT, "사진 칸(slotRects)은 3개 또는 4개여야 합니다.");
        }
        for (SlotRect s : slots) {
            if (s == null || s.x() < 0 || s.y() < 0 || s.w() <= 0 || s.h() <= 0
                    || s.x() + s.w() > CANVAS_WIDTH || s.y() + s.h() > CANVAS_HEIGHT) {
                throw new BadRequestException(ErrorCode.INVALID_INPUT,
                        "사진 칸은 " + CANVAS_WIDTH + "×" + CANVAS_HEIGHT + " 캔버스 안의 양수 크기 사각형이어야 합니다.");
            }
        }
    }

    // 상시는 언제나, 스페셜은 from <= today <= until
    public boolean isAvailableOn(LocalDate today) {
        return kind == FrameKind.ORIGINAL || (!today.isBefore(availableFrom) && !today.isAfter(availableUntil));
    }

    public void softDelete() {
        super.delete(Instant.now());
    }
}
