package com.stagelog.common.locale;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SupportedLocaleTest {

    @ParameterizedTest
    @CsvSource({"ko,KO", "KO,KO", "ko-KR,KO", "en,EN", "en-US,EN", "zh-TW,ZH_TW", "zh-tw,ZH_TW", "zh_TW,ZH_TW",
            "zh-Hant-TW,ZH_TW", "zh-Hant,ZH_TW", "zh,ZH_TW", "ZH,ZH_TW", "ja,JA", "ja-JP,JA"})
    void fromTag_normalizes_bcp47(String tag, SupportedLocale expected) {
        assertThat(SupportedLocale.fromTag(tag)).contains(expected);
    }

    @Test
    void fromTag_rejects_unsupported() {
        assertThat(SupportedLocale.fromTag("zh-CN")).isEmpty();
        assertThat(SupportedLocale.fromTag("zh-Hans")).isEmpty();
        assertThat(SupportedLocale.fromTag("zh-Hans-CN")).isEmpty();
        assertThat(SupportedLocale.fromTag("fr")).isEmpty();
        assertThat(SupportedLocale.fromTag("")).isEmpty();
        assertThat(SupportedLocale.fromTag(null)).isEmpty();
    }

    @Test
    void fallback_chains() {
        assertThat(SupportedLocale.ZH_TW.fallbackChain()).containsExactly(SupportedLocale.EN, SupportedLocale.KO);
        assertThat(SupportedLocale.JA.fallbackChain()).containsExactly(SupportedLocale.EN, SupportedLocale.KO);
        assertThat(SupportedLocale.EN.fallbackChain()).containsExactly(SupportedLocale.KO);
        assertThat(SupportedLocale.KO.fallbackChain()).isEqualTo(List.of());
    }
}
