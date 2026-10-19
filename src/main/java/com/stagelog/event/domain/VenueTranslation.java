package com.stagelog.event.domain;

import com.stagelog.common.entity.BaseEntity;
import com.stagelog.common.locale.SupportedLocale;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 공연장 번역. 필드(NAME/CITY)·로케일당 한 행.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "venue_translations")
public class VenueTranslation extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "venue_id", nullable = false)
    private Venue venue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TranslationField field;

    @Convert(converter = SupportedLocaleConverter.class)
    @Column(nullable = false, length = 10)
    private SupportedLocale locale;

    @Column(nullable = false, length = 500)
    private String text;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TranslationSource source;

    @Column(nullable = false)
    private boolean reviewed;

    public VenueTranslation(Venue venue, TranslationField field, SupportedLocale locale, String text,
                            TranslationSource source, boolean reviewed) {
        if (field == TranslationField.DESCRIPTION) {
            throw new IllegalArgumentException("공연장 번역 필드는 NAME 또는 CITY 입니다");
        }
        this.venue = venue;
        this.field = field;
        this.locale = locale;
        this.text = text;
        this.source = source;
        this.reviewed = reviewed;
    }

    public void update(String text, TranslationSource source, boolean reviewed) {
        this.text = text;
        this.source = source;
        this.reviewed = reviewed;
    }
}
