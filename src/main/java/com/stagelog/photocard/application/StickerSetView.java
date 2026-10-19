package com.stagelog.photocard.application;

import com.stagelog.common.locale.LocalizedText;
import com.stagelog.common.locale.SupportedLocale;
import com.stagelog.photocard.application.port.ImageStoragePort;
import com.stagelog.photocard.domain.StickerSet;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 프론트 StickerSet 계약과 같은 모양의 읽기 스냅샷. name 은 원어 로케일 키를 포함한 LocalizedText.
 */
public record StickerSetView(Long id, LocalizedText name, String season, LocalDate availableFrom, LocalDate availableTo,
                             boolean isDefault, List<StickerView> stickers) {

    // name 은 세트 원어 로케일의 이름(없으면 null)
    public record StickerView(Long id, Long setId, String imageUrl, LocalizedText name, String category, String group) {
    }

    static StickerSetView of(StickerSet set, ImageStoragePort images) {
        EnumMap<SupportedLocale, String> map = new EnumMap<>(SupportedLocale.class);
        SupportedLocale.fromTag(set.getOriginalLocale()).ifPresent(locale -> map.put(locale, set.getName()));
        set.getTranslations().forEach(t -> SupportedLocale.fromTag(t.getLocale()).ifPresent(locale -> map.put(locale, t.getText())));
        SupportedLocale original = SupportedLocale.fromTag(set.getOriginalLocale()).orElse(SupportedLocale.KO);
        List<StickerView> stickers = set.getStickers().stream()
                .map(s -> new StickerView(s.getId(), set.getId(), images.publicUrl(s.getImageKey()),
                        s.getName() == null ? null : new LocalizedText(s.getName(), Map.of(original, s.getName())), s.getCategory(), s.getGroup()))
                .toList();
        return new StickerSetView(set.getId(), new LocalizedText(set.getName(), map), set.getSeason(), set.getAvailableFrom(),
                set.getAvailableUntil(), set.isDefault(), stickers);
    }
}
