package com.stagelog.photocard.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/**
 * PlacedSticker 목록 <-> JSON 배열 문자열 (photocards.stickers 컬럼).
 */
@Converter
public class PlacedStickersConverter implements AttributeConverter<List<PlacedSticker>, String> {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Override
    public String convertToDatabaseColumn(List<PlacedSticker> attribute) {
        return MAPPER.writeValueAsString(attribute == null ? List.of() : attribute);
    }

    @Override
    public List<PlacedSticker> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return List.of();
        }
        return List.of(MAPPER.readValue(dbData, PlacedSticker[].class));
    }
}
