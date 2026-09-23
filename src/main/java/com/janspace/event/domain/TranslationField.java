package com.janspace.event.domain;

/**
 * 번역 대상 필드. 행사는 NAME/DESCRIPTION, 공연장은 NAME/CITY 만 허용하며 각 엔티티가 검증한다.
 */
public enum TranslationField {
    NAME, DESCRIPTION, CITY
}
