package com.stagelog.photocard.application;

import com.stagelog.common.exception.ErrorCode;
import com.stagelog.common.exception.predefined.BadRequestException;
import com.stagelog.common.exception.predefined.NotFoundException;
import com.stagelog.common.locale.LocalizedText;
import com.stagelog.common.locale.SupportedLocale;
import com.stagelog.media.application.ImageValidator;
import com.stagelog.photocard.application.port.ImageStoragePort;
import com.stagelog.photocard.domain.StickerSet;
import com.stagelog.photocard.domain.StickerSetRepository;
import com.stagelog.photocard.domain.TranslationSource;
import java.util.ArrayList;
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
    private static final int NAME_MAX = 100;

    private final StickerSetRepository stickerSets;
    private final ImageStoragePort images;
    private final int maxPx;

    public AdminStickerCommand(StickerSetRepository stickerSets, ImageStoragePort images,
                               @Value("${stagelog.photocard.max-px:1350}") int maxPx) {
        this.stickerSets = stickerSets;
        this.images = images;
        this.maxPx = maxPx;
    }

    @Transactional
    public StickerSetView upsert(UpsertStickerSet cmd, List<byte[]> stickerFiles) {
        String originalLocale = localeOrDefault(cmd.originalLocale());
        // 1. 입력과 파일은 저장 전에 전부 검증한다 (하나라도 틀리면 아무것도 올리지 않는다)
        validateName(cmd.name());
        if (cmd.newStickers() != null && cmd.newStickers().size() != stickerFiles.size()) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT, "newStickers 는 stickers 파일과 개수·순서가 같아야 합니다.");
        }
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

        // 3. 외부 호출: 스티커 S3 업로드, 업로드 순서대로 뒤에 붙인다. 중간 업로드나 저장이 실패해 롤백되면 이미 올린 객체를 지운다
        List<String> keys = new ArrayList<>();
        for (ImageValidator.Format format : formats) {
            keys.add("stickers/" + set.getId() + "/" + UUID.randomUUID() + "." + format.extension());
        }
        StorageCleanup.afterCompletion(images, List.of(), keys);
        for (int i = 0; i < stickerFiles.size(); i++) {
            String key = keys.get(i);
            images.put(key, stickerFiles.get(i), formats.get(i).contentType());
            set.addSticker(key, cmd.newStickers() == null ? null : cmd.newStickers().get(i));
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

    // 세트 이름 원어·번역은 sticker_sets.name·sticker_set_translations.text 의 길이(100) 안
    private static void validateName(LocalizedText name) {
        if (name.original().isBlank() || name.original().trim().length() > NAME_MAX
                || name.translations().values().stream().anyMatch(text -> text.trim().length() > NAME_MAX)) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT, "name 은 비어 있지 않은 " + NAME_MAX + "자 이하여야 합니다.");
        }
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
