package com.janspace.photocard.application;

import com.janspace.common.locale.LocalizedText;
import com.janspace.photocard.domain.StickerLabel;
import java.time.LocalDate;
import java.util.List;

/**
 * 관리자 스티커 세트 upsert 입력. id 가 있으면 그 세트를 갱신하고 새 파일을 뒤에 붙인다. isDefault 가 null 이면 false.
 * newStickers 는 새 파일과 같은 순서의 이름·분류이며, null 이면 라벨 없이 올린다.
 */
public record UpsertStickerSet(Long id, LocalizedText name, String originalLocale, String season, LocalDate availableFrom,
                               LocalDate availableTo, Boolean isDefault, List<StickerLabel> newStickers) {
}
