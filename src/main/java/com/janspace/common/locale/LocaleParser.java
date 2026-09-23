package com.janspace.common.locale;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Accept-Language 헤더에서 지원 로케일을 고른다. 품질값(q) 순서로 처음 매칭되는 로케일을 반환하며 없으면 empty.
 */
public final class LocaleParser {

    private LocaleParser() {
    }

    public static Optional<SupportedLocale> parse(String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            return Optional.empty();
        }
        // 1. q 값 기준 정렬은 JDK 파서에 맡긴다. 형식이 깨진 헤더는 무시
        List<Locale.LanguageRange> ranges;
        try {
            ranges = Locale.LanguageRange.parse(acceptLanguage);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        // 2. 우선순위대로 지원 로케일에 매핑. "*" 는 선호 없음이므로 건너뜀
        for (Locale.LanguageRange range : ranges) {
            if ("*".equals(range.getRange())) {
                continue;
            }
            Optional<SupportedLocale> matched = SupportedLocale.fromTag(range.getRange());
            if (matched.isPresent()) {
                return matched;
            }
        }
        return Optional.empty();
    }
}
