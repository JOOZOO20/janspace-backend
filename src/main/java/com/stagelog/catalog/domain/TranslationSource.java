package com.stagelog.catalog.domain;

/**
 * 번역 텍스트의 출처. 검수 여부(reviewed)와 별개로 어디서 왔는지를 남긴다.
 */
public enum TranslationSource {
    PROVIDER,
    MACHINE,
    ADMIN
}
