package com.stagelog.photocard.application;

import com.stagelog.photocard.domain.PhotocardKind;
import com.stagelog.photocard.domain.PlacedSticker;
import java.util.List;

/**
 * 포토카드 생성 입력(이미지 바이트 제외). kind 가 null 이면 CARD. frameId 는 FOURCUT 일 때만 쓴다.
 */
public record CreatePhotocard(PhotocardKind kind, String backgroundColor, Long frameId, List<PlacedSticker> stickers,
                              Long linkedRecordId) {
}
