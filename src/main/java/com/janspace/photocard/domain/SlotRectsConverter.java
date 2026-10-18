package com.janspace.photocard.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/**
 * SlotRect 목록 <-> JSON 배열 문자열 (frames.slot_rects 컬럼).
 */
@Converter
public class SlotRectsConverter implements AttributeConverter<List<SlotRect>, String> {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Override
    public String convertToDatabaseColumn(List<SlotRect> attribute) {
        return MAPPER.writeValueAsString(attribute == null ? List.of() : attribute);
    }

    @Override
    public List<SlotRect> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return List.of();
        }
        return List.of(MAPPER.readValue(dbData, SlotRect[].class));
    }
}
