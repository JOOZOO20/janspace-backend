package com.janspace.photocard.application;

import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.BadRequestException;
import com.janspace.common.exception.predefined.NotFoundException;
import com.janspace.common.locale.SupportedLocale;
import com.janspace.photocard.application.port.ImageStoragePort;
import com.janspace.photocard.domain.ImageValidator;
import com.janspace.photocard.domain.StickerSet;
import com.janspace.photocard.domain.StickerSetRepository;
import com.janspace.photocard.domain.TranslationSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 스티커 세트 관리: 세트 upsert + 이름 번역 + 스티커 파일 업로드(stickers/{setId}/{uuid}.{ext}), 스티커 삭제.
 */
@Slf4j
@Service
public class AdminStickerCommand {

    private static final String DEFAULT_LOCALE = SupportedLocale.KO.tag();

    private final StickerSetRepository stickerSets;
    private final ImageStoragePort images;
    private final int maxPx;

    public AdminStickerCommand(StickerSetRepository stickerSets, ImageStoragePort images,
                               @Value("${janspace.photocard.max-px:1350}") int maxPx) {
        this.stickerSets = stickerSets;
        this.images = images;
        this.maxPx = maxPx;
    }

    @Transactional
    public StickerSetView upsert(UpsertStickerSet cmd, List<byte[]> stickerFiles) {
        String originalLocale = localeOrDefault(cmd.originalLocale());
        // 1. 파일은 저장 전에 전부 검증한다 (하나라도 틀리면 아무것도 올리지 않는다)
        List<ImageValidator.Format> formats = stickerFiles.stream().map(bytes -> ImageValidator.validate(bytes, maxPx)).toList();

        // 2. 대상 세트: id 우선, 없으면 신규
        StickerSet set = cmd.id() == null ? new StickerSet(cmd.name().original().trim(), originalLocale)
                : stickerSets.findByIdWithDetails(cmd.id()).orElseThrow(() -> new NotFoundException(ErrorCode.STICKER_SET_NOT_FOUND));
        set.update(cmd.name().original().trim(), originalLocale, blankToNull(cmd.season()), cmd.availableFrom(), cmd.availableTo(),
                Boolean.TRUE.equals(cmd.isDefault()));
        Map<String, String> translations = new HashMap<>();
        cmd.name().translations().forEach((locale, text) -> translations.put(locale.tag(), text));
        set.replaceTranslations(translations, TranslationSource.ADMIN);
        // 신규 세트는 키에 id 가 필요해 먼저 저장한다
        set = stickerSets.save(set);

        // 3. 외부 호출: 스티커 S3 업로드, 업로드 순서대로 뒤에 붙인다
        for (int i = 0; i < stickerFiles.size(); i++) {
            String key = "stickers/" + set.getId() + "/" + UUID.randomUUID() + "." + formats.get(i).extension();
            images.put(key, stickerFiles.get(i), formats.get(i).contentType());
            set.addSticker(key);
        }
        StickerSet saved = stickerSets.save(set);
        log.info("[스티커 세트 upsert] setId={}, 신규={}, 추가 파일={}, 전체 스티커={}", saved.getId(), cmd.id() == null, stickerFiles.size(),
                saved.getStickers().size());
        return StickerSetView.of(saved, images);
    }

    @Transactional
    public void deleteSticker(Long setId, Long stickerId) {
        StickerSet set = stickerSets.findByIdWithDetails(setId).orElseThrow(() -> new NotFoundException(ErrorCode.STICKER_SET_NOT_FOUND));
        if (!set.removeSticker(stickerId)) {
            throw new NotFoundException(ErrorCode.STICKER_NOT_FOUND);
        }
        log.info("[스티커 삭제] setId={}, stickerId={}", setId, stickerId);
    }

    private static String localeOrDefault(String tag) {
        if (tag == null || tag.isBlank()) {
            return DEFAULT_LOCALE;
        }
        return SupportedLocale.fromTag(tag).map(SupportedLocale::tag)
                .orElseThrow(() -> new BadRequestException(ErrorCode.INVALID_INPUT, "지원하지 않는 로케일입니다: " + tag));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
