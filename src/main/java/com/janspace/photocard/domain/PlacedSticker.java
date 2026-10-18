package com.janspace.photocard.domain;

/**
 * 포토카드 위 스티커 배치 하나. stickerId 는 서버 스티커 ID(숫자 문자열) 또는 앱이 그리는 코드 스티커 ID(code:*)다.
 * 좌표·크기·회전의 단위는 프론트가 정한다(백엔드는 통째로 저장·반환만 한다).
 */
public record PlacedSticker(String stickerId, double x, double y, double scale, double rotation) {
}
