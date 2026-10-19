package com.stagelog.photocard.domain;

/**
 * 번역 텍스트의 출처. catalog·event 의 같은 이름 enum 은 기능 경계 밖이라 import 할 수 없어 photocard 가 자기 것을 둔다.
 */
public enum TranslationSource {
    PROVIDER,
    MACHINE,
    ADMIN
}
