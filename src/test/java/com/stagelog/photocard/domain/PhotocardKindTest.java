package com.stagelog.photocard.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PhotocardKindTest {

    @Test
    @DisplayName("카드는 4:5, 네컷은 1:3 이고 높이 1px 반올림 오차만 허용한다")
    void ratio() {
        assertThat(PhotocardKind.CARD.matchesRatio(1080, 1350)).isTrue();
        assertThat(PhotocardKind.CARD.matchesRatio(1080, 1351)).isTrue();
        assertThat(PhotocardKind.CARD.matchesRatio(1080, 1349)).isTrue();
        assertThat(PhotocardKind.CARD.matchesRatio(1080, 1352)).isFalse();
        assertThat(PhotocardKind.CARD.matchesRatio(1080, 3240)).isFalse();
        assertThat(PhotocardKind.CARD.matchesRatio(120, 80)).isFalse();

        assertThat(PhotocardKind.FOURCUT.matchesRatio(1080, 3240)).isTrue();
        assertThat(PhotocardKind.FOURCUT.matchesRatio(1080, 3241)).isTrue();
        assertThat(PhotocardKind.FOURCUT.matchesRatio(1080, 3242)).isFalse();
        assertThat(PhotocardKind.FOURCUT.matchesRatio(1080, 1350)).isFalse();
    }
}
