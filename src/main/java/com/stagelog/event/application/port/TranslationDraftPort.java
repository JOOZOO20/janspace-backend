package com.stagelog.event.application.port;

import com.stagelog.common.locale.SupportedLocale;
import java.util.Map;
import java.util.Set;

/**
 * 기계 번역 초안. 만들지 못한 로케일은 결과에서 빠진다 (키 없음이면 빈 맵).
 */
public interface TranslationDraftPort {

    Map<SupportedLocale, String> draft(String text, SupportedLocale from, Set<SupportedLocale> to);
}
