package com.janspace.common.locale;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 서비스가 지원하는 로케일과 번역 폴백 순서. 태그는 BCP-47 정규형이며 비교는 대소문자를 무시한다.
 */
public enum SupportedLocale {

    KO("ko"),
    EN("en"),
    ZH_TW("zh-TW"),
    JA("ja");

    private final String tag;

    SupportedLocale(String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }

    // "zh-tw", "ZH_TW", "zh-Hant-TW" 같은 변형을 정규형으로 맞춰 비교한다
    public static Optional<SupportedLocale> fromTag(String rawTag) {
        if (rawTag == null || rawTag.isBlank()) {
            return Optional.empty();
        }
        Locale locale = Locale.forLanguageTag(rawTag.trim().replace('_', '-'));
        String language = locale.getLanguage();
        // 중국어는 대만(번체)만 지원. 스크립트 Hant 또는 지역 TW 만 허용
        if ("zh".equals(language)) {
            boolean traditional = "Hant".equals(locale.getScript()) || "TW".equalsIgnoreCase(locale.getCountry());
            return traditional ? Optional.of(ZH_TW) : Optional.empty();
        }
        for (SupportedLocale candidate : values()) {
            if (candidate != ZH_TW && candidate.tag.equalsIgnoreCase(language)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    // 요청 로케일에 번역이 없을 때 차례로 시도할 로케일. 자기 자신은 포함하지 않으며 끝은 항상 원어다
    public List<SupportedLocale> fallbackChain() {
        return switch (this) {
            case ZH_TW, JA -> List.of(EN, KO);
            case EN -> List.of(KO);
            case KO -> List.of();
        };
    }
}
