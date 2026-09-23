package com.janspace.event.domain;

import jakarta.persistence.AttributeConverter;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/**
 * enum 집합 <-> JSON 문자열 배열 (locked_fields 컬럼). 이름을 정렬해 저장하므로 같은 집합은 같은 JSON 이 된다.
 */
abstract class EnumSetJsonConverter<E extends Enum<E>> implements AttributeConverter<Set<E>, String> {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final Class<E> type;

    protected EnumSetJsonConverter(Class<E> type) {
        this.type = type;
    }

    @Override
    public String convertToDatabaseColumn(Set<E> attribute) {
        List<String> names = attribute == null ? List.of() : attribute.stream().map(Enum::name).sorted().toList();
        return MAPPER.writeValueAsString(names);
    }

    @Override
    public Set<E> convertToEntityAttribute(String dbData) {
        EnumSet<E> set = EnumSet.noneOf(type);
        if (dbData == null || dbData.isBlank()) {
            return set;
        }
        for (String name : MAPPER.readValue(dbData, String[].class)) {
            set.add(Enum.valueOf(type, name));
        }
        return set;
    }
}
