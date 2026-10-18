package com.janspace.photocard.application;

import com.janspace.common.locale.LocalizedText;
import java.time.LocalDate;

/**
 * 관리자 스티커 세트 upsert 입력. id 가 있으면 그 세트를 갱신하고 새 파일을 뒤에 붙인다. isDefault 가 null 이면 false.
 */
public record UpsertStickerSet(Long id, LocalizedText name, String originalLocale, String season, LocalDate availableFrom,
                               LocalDate availableTo, Boolean isDefault) {
}
