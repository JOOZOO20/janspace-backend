package com.janspace.photocard.domain;

import com.janspace.common.entity.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 스티커 세트(기본 세트 + 시즌 세트). 배포 기간 안에서만 공개 목록에 나온다. 이름 원어는 본 컬럼, 번역은 sticker_set_translations.
 * 관리자 마스터 데이터라 물리 삭제 경로가 없어 BaseEntity 만 상속한다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "sticker_sets")
public class StickerSet extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "original_locale", nullable = false, length = 10)
    private String originalLocale;

    @Column(length = 50)
    private String season;

    @Column(name = "available_from")
    private LocalDate availableFrom;

    @Column(name = "available_until")
    private LocalDate availableUntil;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    // 번역은 Set, 스티커는 List: List 두 개를 fetch join 하면 MultipleBagFetchException 이 난다
    @OneToMany(mappedBy = "stickerSet", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<StickerSetTranslation> translations = new HashSet<>();

    @OneToMany(mappedBy = "stickerSet", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder asc, id asc")
    private List<Sticker> stickers = new ArrayList<>();

    public StickerSet(String name, String originalLocale) {
        this.name = name;
        this.originalLocale = originalLocale;
    }

    public void update(String name, String originalLocale, String season, LocalDate availableFrom, LocalDate availableUntil,
                       boolean isDefault) {
        this.name = name;
        this.originalLocale = originalLocale;
        this.season = season;
        this.availableFrom = availableFrom;
        this.availableUntil = availableUntil;
        this.isDefault = isDefault;
    }

    // 로케일 -> 텍스트 전체 교체. 원어 로케일은 본 컬럼이 담당한다. 같은 로케일 행은 제자리 갱신(지우고 넣으면 INSERT 가 DELETE 보다 먼저 흘러 유일 제약에 걸린다)
    public void replaceTranslations(Map<String, String> byLocale, TranslationSource source) {
        Map<String, String> wanted = new HashMap<>();
        byLocale.forEach((locale, text) -> {
            if (!locale.equals(originalLocale) && text != null && !text.isBlank()) {
                wanted.put(locale, text.trim());
            }
        });
        translations.removeIf(existing -> !wanted.containsKey(existing.getLocale()));
        translations.forEach(existing -> existing.changeText(wanted.remove(existing.getLocale()), source));
        wanted.forEach((locale, text) -> translations.add(new StickerSetTranslation(this, locale, text, source)));
    }

    // 업로드 순서대로 뒤에 붙인다. label 은 없을 수 있다
    public Sticker addSticker(String imageKey, StickerLabel label) {
        int next = stickers.stream().mapToInt(Sticker::getSortOrder).max().orElse(-1) + 1;
        Sticker sticker = new Sticker(this, imageKey, next, label);
        stickers.add(sticker);
        return sticker;
    }

    public boolean removeSticker(Long stickerId) {
        return stickers.removeIf(s -> s.getId().equals(stickerId));
    }

    // 배포 기간: from <= today <= until, NULL 은 열린 쪽
    public boolean isAvailableOn(LocalDate today) {
        return (availableFrom == null || !today.isBefore(availableFrom))
                && (availableUntil == null || !today.isAfter(availableUntil));
    }
}
