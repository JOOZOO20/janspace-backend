package com.stagelog.account.domain;

import com.stagelog.common.locale.SupportedLocale;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * users.locale 에는 enum 이름(KO)이 아니라 BCP-47 태그(ko)를 저장한다. null(기기 언어)은 NULL 그대로다.
 */
@Converter
public class SupportedLocaleConverter implements AttributeConverter<SupportedLocale, String> {

    @Override
    public String convertToDatabaseColumn(SupportedLocale locale) {
        return locale == null ? null : locale.tag();
    }

    @Override
    public SupportedLocale convertToEntityAttribute(String tag) {
        return SupportedLocale.fromTag(tag).orElse(null);
    }
}
