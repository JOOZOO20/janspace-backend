package com.stagelog.photocard.domain;

import com.stagelog.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 스티커 세트 이름 번역 한 행. (sticker_set, field, locale) 유일. 지금은 NAME 만 번역한다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "sticker_set_translations",
        uniqueConstraints = @UniqueConstraint(name = "uk_sticker_set_translations_field_locale",
                columnNames = {"sticker_set_id", "field", "locale"}))
public class StickerSetTranslation extends BaseEntity {

    public enum Field {
        NAME
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sticker_set_id", nullable = false)
    private StickerSet stickerSet;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Field field = Field.NAME;

    @Column(nullable = false, length = 10)
    private String locale;

    @Column(nullable = false, length = 100)
    private String text;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TranslationSource source;

    @Column(nullable = false)
    private boolean reviewed;

    StickerSetTranslation(StickerSet stickerSet, String locale, String text, TranslationSource source) {
        this.stickerSet = stickerSet;
        this.locale = locale;
        this.text = text;
        this.source = source;
        // 관리자가 직접 넣은 번역은 검수된 것으로 본다
        this.reviewed = source == TranslationSource.ADMIN;
    }

    void changeText(String text, TranslationSource source) {
        this.text = text;
        this.source = source;
        this.reviewed = source == TranslationSource.ADMIN;
    }
}
