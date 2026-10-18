package com.janspace.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AliasNormalizerTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "  For Lovers Who Hesitate  | for lovers who hesitate",
            "\"Bad   Dreams\"           | bad dreams",
            "LEGEND!                   | legend",
            "'(Legend)'                | (legend)",
            "주저하는  연인들을 위해     | 주저하는 연인들을 위해",
            "She (feat. X)             | she (feat. x)",
    })
    void normalizes_case_whitespace_and_edge_punctuation(String raw, String expected) {
        assertThat(AliasNormalizer.normalize(raw)).isEqualTo(expected);
    }

    @Test
    @DisplayName("DB 콜레이션(utf8mb4_0900_ai_ci)처럼 악센트를 무시하고, 조합형(NFD)·완성형 입력을 같게 본다. 한글은 완성형으로 남는다")
    void accent_insensitive_and_nfc() {
        assertThat(AliasNormalizer.normalize("Café")).isEqualTo("cafe");
        assertThat(AliasNormalizer.normalize("Cafe\u0301")).isEqualTo("cafe");
        assertThat(AliasNormalizer.normalize("\u1100\u1161\u11A8")).isEqualTo("각");
        assertThat(AliasNormalizer.normalize("주저하는 연인들을 위해")).isEqualTo("주저하는 연인들을 위해");
    }

    @Test
    void null_and_blank_become_empty() {
        assertThat(AliasNormalizer.normalize(null)).isEmpty();
        assertThat(AliasNormalizer.normalize("   ")).isEmpty();
        assertThat(AliasNormalizer.normalize("\"\"")).isEmpty();
    }
}
