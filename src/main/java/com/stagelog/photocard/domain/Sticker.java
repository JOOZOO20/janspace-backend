package com.stagelog.photocard.domain;

import com.stagelog.common.entity.BaseEntity;
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
 * name 은 화면 읽기용 이름(세트의 원어 로케일), category·group 은 편집기 서랍의 종류와 그 안의 묶음이다. 셋 다 없을 수 있다.
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

    @Column(length = 100)
    private String name;

    @Column(length = 30)
    private String category;

    // group 은 SQL 예약어라 컬럼 이름을 바꾼다
    @Column(name = "sticker_group", length = 30)
    private String group;

    Sticker(StickerSet stickerSet, String imageKey, int sortOrder, StickerLabel label) {
        this.stickerSet = stickerSet;
        this.imageKey = imageKey;
        this.sortOrder = sortOrder;
        if (label != null) {
            this.name = label.name();
            this.category = label.category();
            this.group = label.group();
        }
    }
}
