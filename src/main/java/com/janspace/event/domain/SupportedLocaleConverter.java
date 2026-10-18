package com.janspace.event.domain;

import com.janspace.common.locale.SupportedLocale;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * SupportedLocale <-> BCP-47 태그 문자열 (locale, original_locale 컬럼).
 */
@Converter
public class SupportedLocaleConverter implements AttributeConverter<SupportedLocale, String> {

    @Override
    public String convertToDatabaseColumn(SupportedLocale attribute) {
        return attribute == null ? null : attribute.tag();
    }

    @Override
    public SupportedLocale convertToEntityAttribute(String dbData) {
        return dbData == null ? null : SupportedLocale.fromTag(dbData)
                .orElseThrow(() -> new IllegalStateException("지원하지 않는 로케일이 저장돼 있습니다: " + dbData));
    }
}
