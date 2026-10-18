package com.janspace.event.infrastructure;

import com.janspace.common.locale.SupportedLocale;
import com.janspace.event.application.port.TranslationDraftPort;
import com.janspace.integration.deepl.DeepLClient;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * TranslationDraftPort 의 DeepL 구현. 로케일 -> DeepL 언어 코드 매핑을 맡는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeepLTranslationAdapter implements TranslationDraftPort {

    private final DeepLClient deepLClient;

    @Override
    public Map<SupportedLocale, String> draft(String text, SupportedLocale from, Set<SupportedLocale> to) {
        Map<SupportedLocale, String> drafts = new EnumMap<>(SupportedLocale.class);
        if (!deepLClient.isEnabled()) {
            return drafts;
        }
        for (SupportedLocale target : to) {
            if (target == from) {
                continue;
            }
            List<String> translated = deepLClient.translate(List.of(text), sourceCode(from), targetCode(target));
            if (!translated.isEmpty()) {
                drafts.put(target, translated.get(0));
            }
        }
        log.info("[번역 초안] from={}, drafted={}", from, drafts.keySet());
        return drafts;
    }

    static String sourceCode(SupportedLocale locale) {
        return switch (locale) {
            case KO -> "KO";
            case EN -> "EN";
            case JA -> "JA";
            case ZH_TW -> "ZH";
        };
    }

    static String targetCode(SupportedLocale locale) {
        return switch (locale) {
            case KO -> "KO";
            case EN -> "EN";
            case JA -> "JA";
            case ZH_TW -> "ZH-HANT";
        };
    }
}
