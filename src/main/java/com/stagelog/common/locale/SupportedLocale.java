package com.stagelog.common.locale;

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

    // "zh", "zh-tw", "ZH_TW", "zh-Hant-TW" 같은 변형을 정규형으로 맞춰 비교한다
    public static Optional<SupportedLocale> fromTag(String rawTag) {
        if (rawTag == null || rawTag.isBlank()) {
            return Optional.empty();
        }
        Locale locale = Locale.forLanguageTag(rawTag.trim().replace('_', '-'));
        String language = locale.getLanguage();
        // 중국어는 대만(번체)만 지원한다. 지역·스크립트가 없는 "zh"(프론트 Lang 값)는 zh-TW 로 정규화하고,
        // 간체(Hans)나 다른 지역(zh-CN 등)은 거부한다
        if ("zh".equals(language)) {
            String script = locale.getScript();
            String country = locale.getCountry();
            boolean bare = script.isEmpty() && country.isEmpty();
            boolean traditional = "Hant".equals(script) || "TW".equalsIgnoreCase(country);
            return bare || traditional ? Optional.of(ZH_TW) : Optional.empty();
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
