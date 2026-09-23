package com.janspace.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

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
    void null_and_blank_become_empty() {
        assertThat(AliasNormalizer.normalize(null)).isEmpty();
        assertThat(AliasNormalizer.normalize("   ")).isEmpty();
        assertThat(AliasNormalizer.normalize("\"\"")).isEmpty();
    }
}
