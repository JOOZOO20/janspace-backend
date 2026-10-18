package com.janspace.common.locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.json.JsonMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocalizedTextTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void resolve_applies_fallback_chain_then_original() {
        LocalizedText onlyKo = new LocalizedText("원어", Map.of(SupportedLocale.KO, "한국어"));
        assertThat(onlyKo.resolve(SupportedLocale.ZH_TW)).isEqualTo("한국어");
        assertThat(onlyKo.resolve(SupportedLocale.JA)).isEqualTo("한국어");
        assertThat(onlyKo.resolve(SupportedLocale.KO)).isEqualTo("한국어");

        LocalizedText withEn = new LocalizedText("원어", Map.of(SupportedLocale.EN, "English", SupportedLocale.KO, "한국어"));
        assertThat(withEn.resolve(SupportedLocale.ZH_TW)).isEqualTo("English");

        LocalizedText none = LocalizedText.of("원어");
        assertThat(none.resolve(SupportedLocale.JA)).isEqualTo("원어");
        assertThat(none.resolve(null)).isEqualTo("원어");
    }

    @Test
    void json_round_trip_with_zh_TW_key_and_only_present_keys() throws Exception {
        LocalizedText text = new LocalizedText("잔나비", Map.of(SupportedLocale.ZH_TW, "簡單", SupportedLocale.EN, "Jannabi"));
        String json = mapper.writeValueAsString(text);
        assertThat(json).isEqualTo("{\"original\":\"잔나비\",\"en\":\"Jannabi\",\"zh-TW\":\"簡單\"}");

        LocalizedText back = mapper.readValue(json, LocalizedText.class);
        assertThat(back).isEqualTo(text);
        assertThat(mapper.readValue("{\"original\":\"x\",\"fr\":\"ignored\"}", LocalizedText.class))
                .isEqualTo(LocalizedText.of("x"));
    }

    @Test
    void original_is_required() {
        assertThatThrownBy(() -> new LocalizedText(null, Map.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> mapper.readValue("{\"ko\":\"x\"}", LocalizedText.class))
                .hasMessageContaining("original");
    }
}
