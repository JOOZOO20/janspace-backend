package com.janspace.event.application;

import com.janspace.common.locale.LocalizedText;
import com.janspace.common.locale.SupportedLocale;
import com.janspace.event.application.port.TranslationDraftPort;
import com.janspace.event.domain.TranslationSource;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 관리자 입력 문자열의 번역 행 결정: 관리자가 번역을 함께 주면 ADMIN·검수됨, 원어만 주면 기계 초안(MACHINE·미검수).
 * 초안 실패는 관리자 작업을 막지 않는다 (경고 로그 후 빈 결과).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TranslationDrafts {

    private final TranslationDraftPort translationDraftPort;

    public record Drafted(Map<SupportedLocale, String> texts, TranslationSource source, boolean reviewed) {
    }

    public Drafted of(LocalizedText text, SupportedLocale from) {
        if (!text.translations().isEmpty()) {
            return new Drafted(text.translations(), TranslationSource.ADMIN, true);
        }
        Set<SupportedLocale> targets = EnumSet.allOf(SupportedLocale.class);
        targets.remove(from);
        try {
            // 외부 호출 (DeepL). 키가 없으면 어댑터가 빈 맵을 준다
            return new Drafted(translationDraftPort.draft(text.original(), from, targets), TranslationSource.MACHINE, false);
        } catch (RuntimeException e) {
            log.warn("[번역 초안 실패] 원어 길이={}", text.original().length(), e);
            return new Drafted(Map.of(), TranslationSource.MACHINE, false);
        }
    }
}
