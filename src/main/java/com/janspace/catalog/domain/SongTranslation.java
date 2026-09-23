package com.janspace.catalog.domain;

import com.janspace.common.entity.BaseEntity;
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
 * 곡 필드 번역 한 행. (song, field, locale) 유일.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "song_translations",
        uniqueConstraints = @UniqueConstraint(name = "uk_song_translations_field_locale",
                columnNames = {"song_id", "field", "locale"}))
public class SongTranslation extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "song_id", nullable = false)
    private Song song;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TextField field;

    @Column(nullable = false, length = 10)
    private String locale;

    @Column(nullable = false, length = 255)
    private String text;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TranslationSource source;

    @Column(nullable = false)
    private boolean reviewed;

    SongTranslation(Song song, TextField field, String locale, String text, TranslationSource source) {
        this.song = song;
        this.field = field;
        this.locale = locale;
        this.text = text;
        this.source = source;
        this.reviewed = source == TranslationSource.ADMIN;
    }

    void changeText(String text, TranslationSource source) {
        this.text = text;
        this.source = source;
        this.reviewed = source == TranslationSource.ADMIN;
    }
}
