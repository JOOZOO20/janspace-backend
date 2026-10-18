package com.janspace.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.janspace.common.locale.LocalizedText;
import com.janspace.common.locale.SupportedLocale;
import com.janspace.notification.application.PushMessageRenderer.PushPayload;
import com.janspace.notification.domain.PushKind;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import tools.jackson.databind.ObjectMapper;

/**
 * 로케일별 문구 + 행사 이름 폴백 + 페이로드 kind 매핑.
 */
class PushMessageRendererTest {

    static final LocalizedText NAME = new LocalizedText("잔나비 단독 공연", Map.of(SupportedLocale.EN, "JANNABI Live"));

    PushMessageRenderer renderer = new PushMessageRenderer(messageSource(), new ObjectMapper());

    @Test
    @DisplayName("ko 문구 + 원어 이름, 페이로드 kind 는 프론트 이름(RECORD_REMINDER), url 은 /events/{id}")
    void korean_default() {
        PushPayload p = renderer.payload(PushKind.RECORD, 12L, NAME, SupportedLocale.KO);
        assertThat(p.kind()).isEqualTo("RECORD_REMINDER");
        assertThat(p.eventId()).isEqualTo(12L);
        assertThat(p.title()).isEqualTo("잔나비 단독 공연");
        assertThat(p.body()).contains("별을 남겨");
        assertThat(p.url()).isEqualTo("/events/12");
        assertThat(renderer.render(PushKind.RECORD, 12L, NAME, null))
                .contains("\"kind\":\"RECORD_REMINDER\"").contains("\"eventId\":12").contains("\"url\":\"/events/12\"");
    }

    @Test
    @DisplayName("en / zh-TW / ja 는 각 번들 문구, 이름은 en 번역으로 폴백(zh-TW·ja -> en)")
    void other_locales() {
        PushPayload en = renderer.payload(PushKind.RECORD_FALLBACK, 1L, NAME, SupportedLocale.EN);
        assertThat(en.kind()).isEqualTo("RECORD_REMINDER_FALLBACK");
        assertThat(en.title()).isEqualTo("JANNABI Live");
        assertThat(en.body()).contains("yesterday");

        PushPayload zh = renderer.payload(PushKind.SETLIST_CONFIRMED, 1L, NAME, SupportedLocale.ZH_TW);
        assertThat(zh.kind()).isEqualTo("SETLIST_CONFIRMED");
        assertThat(zh.title()).isEqualTo("JANNABI Live");
        assertThat(zh.body()).contains("歌單");

        PushPayload ja = renderer.payload(PushKind.SETLIST_CONFIRMED, 1L, NAME, SupportedLocale.JA);
        assertThat(ja.title()).isEqualTo("JANNABI Live");
        assertThat(ja.body()).contains("セットリスト");

        // 영어 번역도 없으면 원어
        PushPayload noTranslation = renderer.payload(PushKind.RECORD, 1L, LocalizedText.of("원어만"), SupportedLocale.JA);
        assertThat(noTranslation.title()).isEqualTo("원어만");
    }

    static ResourceBundleMessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("notification/push-messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }
}
