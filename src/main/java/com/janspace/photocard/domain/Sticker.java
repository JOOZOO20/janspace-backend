package com.janspace.photocard.domain;

import com.janspace.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 스티커 이미지 하나. 세트 안에서 sort_order(업로드 순) 로 정렬한다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "stickers")
public class Sticker extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sticker_set_id", nullable = false)
    private StickerSet stickerSet;

    @Column(name = "image_key", nullable = false, length = 255)
    private String imageKey;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    Sticker(StickerSet stickerSet, String imageKey, int sortOrder) {
        this.stickerSet = stickerSet;
        this.imageKey = imageKey;
        this.sortOrder = sortOrder;
    }
}
