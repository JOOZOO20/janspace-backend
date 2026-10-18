package com.janspace.photocard.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlacedStickersConverterTest {

    final PlacedStickersConverter converter = new PlacedStickersConverter();

    @Test
    @DisplayName("PlacedSticker 목록은 JSON 배열로 저장되고 그대로 되돌아온다")
    void round_trip() {
        List<PlacedSticker> placed = List.of(new PlacedSticker(7L, 0.42, 0.65, 1.2, 15.0), new PlacedSticker(9L, 0, 1, 0.5, -90));

        String json = converter.convertToDatabaseColumn(placed);

        assertThat(json).startsWith("[{").contains("\"stickerId\":7").contains("\"rotation\":-90.0");
        assertThat(converter.convertToEntityAttribute(json)).containsExactlyElementsOf(placed);
    }

    @Test
    @DisplayName("null·빈 문자열·빈 목록은 빈 배열로 오간다")
    void empty_cases() {
        assertThat(converter.convertToDatabaseColumn(null)).isEqualTo("[]");
        assertThat(converter.convertToDatabaseColumn(List.of())).isEqualTo("[]");
        assertThat(converter.convertToEntityAttribute(null)).isEmpty();
        assertThat(converter.convertToEntityAttribute("")).isEmpty();
        assertThat(converter.convertToEntityAttribute("[]")).isEmpty();
    }
}
