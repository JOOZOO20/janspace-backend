package com.janspace.photocard.application;

import com.janspace.common.locale.LocalizedText;
import com.janspace.common.locale.SupportedLocale;
import com.janspace.photocard.application.port.ImageStoragePort;
import com.janspace.photocard.domain.StickerSet;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;

/**
 * 프론트 StickerSet 계약과 같은 모양의 읽기 스냅샷. name 은 원어 로케일 키를 포함한 LocalizedText.
 */
public record StickerSetView(Long id, LocalizedText name, String season, LocalDate availableFrom, LocalDate availableTo,
                             boolean isDefault, List<StickerView> stickers) {

    public record StickerView(Long id, Long setId, String imageUrl) {
    }

    static StickerSetView of(StickerSet set, ImageStoragePort images) {
        EnumMap<SupportedLocale, String> map = new EnumMap<>(SupportedLocale.class);
        SupportedLocale.fromTag(set.getOriginalLocale()).ifPresent(locale -> map.put(locale, set.getName()));
        set.getTranslations().forEach(t -> SupportedLocale.fromTag(t.getLocale()).ifPresent(locale -> map.put(locale, t.getText())));
        List<StickerView> stickers = set.getStickers().stream()
                .map(s -> new StickerView(s.getId(), set.getId(), images.publicUrl(s.getImageKey())))
                .toList();
        return new StickerSetView(set.getId(), new LocalizedText(set.getName(), map), set.getSeason(), set.getAvailableFrom(),
                set.getAvailableUntil(), set.isDefault(), stickers);
    }
}
