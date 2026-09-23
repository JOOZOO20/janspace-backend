package com.janspace.event.domain;

import com.janspace.common.entity.BaseEntity;
import com.janspace.common.locale.SupportedLocale;
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
 * 행사 번역. 필드(NAME/DESCRIPTION)·로케일당 한 행.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "event_translations")
public class EventTranslation extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TranslationField field;

    @Convert(converter = SupportedLocaleConverter.class)
    @Column(nullable = false, length = 10)
    private SupportedLocale locale;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String text;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TranslationSource source;

    @Column(nullable = false)
    private boolean reviewed;

    public EventTranslation(Event event, TranslationField field, SupportedLocale locale, String text,
                            TranslationSource source, boolean reviewed) {
        if (field == TranslationField.CITY) {
            throw new IllegalArgumentException("행사 번역 필드는 NAME 또는 DESCRIPTION 입니다");
        }
        this.event = event;
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
