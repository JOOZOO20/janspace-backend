package com.janspace.common.locale;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 원어 문자열과 로케일별 번역을 담는 불변 값. JSON 은 {"original": ..., "ko": ..., "en": ..., "zh-TW": ..., "ja": ...} 이며
 * 번역이 있는 키만 나간다. resolve 는 요청 로케일 -> 폴백 체인 -> 원어 순으로 고른다.
 */
@Schema(description = "원어와 로케일별 번역. 번역이 있는 로케일 키만 포함된다 (ko, en, zh-TW, ja)")
public final class LocalizedText {

    @Schema(description = "원어 텍스트", example = "잔나비 단독 공연", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String original;

    private final Map<SupportedLocale, String> translations;

    public LocalizedText(String original, Map<SupportedLocale, String> translations) {
        this.original = Objects.requireNonNull(original, "original 은 null 일 수 없습니다");
        EnumMap<SupportedLocale, String> copy = new EnumMap<>(SupportedLocale.class);
        if (translations != null) {
            translations.forEach((locale, text) -> {
                if (text != null) {
                    copy.put(locale, text);
                }
            });
        }
        // EnumMap 이라 직렬화 키 순서가 선언 순서(ko, en, zh-TW, ja)로 고정된다. Map.copyOf 는 순서를 보장하지 않는다
        this.translations = Collections.unmodifiableMap(copy);
    }

    public static LocalizedText of(String original) {
        return new LocalizedText(original, Map.of());
    }

    // Jackson 역직렬화. 알 수 없는 로케일 키는 무시한다
    @JsonCreator
    static LocalizedText fromJson(Map<String, String> json) {
        String original = json.get("original");
        if (original == null) {
            throw new IllegalArgumentException("LocalizedText 에는 original 이 필요합니다");
        }
        EnumMap<SupportedLocale, String> translations = new EnumMap<>(SupportedLocale.class);
        json.forEach((key, value) -> {
            if (!"original".equals(key)) {
                SupportedLocale.fromTag(key).ifPresent(locale -> translations.put(locale, value));
            }
        });
        return new LocalizedText(original, translations);
    }

    @JsonProperty("original")
    public String original() {
        return original;
    }

    @JsonIgnore
    public Map<SupportedLocale, String> translations() {
        return translations;
    }

    // 직렬화 시 번역 키를 평면으로 펼친다. EnumMap 순서 = ko, en, zh-TW, ja
    @JsonAnyGetter
    Map<String, String> translationsJson() {
        Map<String, String> out = new LinkedHashMap<>();
        translations.forEach((locale, text) -> out.put(locale.tag(), text));
        return out;
    }

    public Optional<String> translation(SupportedLocale locale) {
        return Optional.ofNullable(translations.get(locale));
    }

    // 요청 로케일 -> 폴백 체인 -> 원어
    public String resolve(SupportedLocale locale) {
        if (locale == null) {
            return original;
        }
        String direct = translations.get(locale);
        if (direct != null) {
            return direct;
        }
        for (SupportedLocale fallback : locale.fallbackChain()) {
            String text = translations.get(fallback);
            if (text != null) {
                return text;
            }
        }
        return original;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof LocalizedText other
                && original.equals(other.original)
                && translations.equals(other.translations);
    }

    @Override
    public int hashCode() {
        return Objects.hash(original, translations);
    }

    @Override
    public String toString() {
        return "LocalizedText{original='" + original + "', translations=" + translations + '}';
    }
}
