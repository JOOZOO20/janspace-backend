package com.janspace.photocard.domain;

/**
 * 업로드 스티커의 이름(화면 읽기용, 세트 원어 로케일)·서랍 종류·묶음. 모두 선택값이다.
 */
public record StickerLabel(String name, String category, String group) {
}
