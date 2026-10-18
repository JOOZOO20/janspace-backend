package com.janspace.photocard.application;

import com.janspace.attendance.application.AttendanceQuery;
import com.janspace.common.exception.ApplicationException;
import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.NotFoundException;
import com.janspace.photocard.application.port.ImageStoragePort;
import com.janspace.photocard.domain.ImageValidator;
import com.janspace.photocard.domain.MonthlyQuotaPolicy;
import com.janspace.photocard.domain.Photocard;
import com.janspace.photocard.domain.PhotocardRepository;
import com.janspace.photocard.domain.PlacedSticker;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 포토카드 생성. 1) 월 제한 2) 이미지 검증 3) 기록 소유 확인 4) S3 저장 5) 행 저장.
 */
@Slf4j
@Service
public class CreatePhotocardUseCase {

    private final PhotocardRepository photocards;
    private final ImageStoragePort images;
    private final AttendanceQuery attendanceQuery;
    private final int perMonth;
    private final int maxPx;

    public CreatePhotocardUseCase(PhotocardRepository photocards, ImageStoragePort images, AttendanceQuery attendanceQuery,
                                  @Value("${janspace.photocard.per-month:10}") int perMonth,
                                  @Value("${janspace.photocard.max-px:1350}") int maxPx) {
        this.photocards = photocards;
        this.images = images;
        this.attendanceQuery = attendanceQuery;
        this.perMonth = perMonth;
        this.maxPx = maxPx;
    }

    @Transactional
    public PhotocardView create(Long userId, byte[] image, List<PlacedSticker> stickers, Long linkedRecordId) {
        // 1. 월 제한 (UTC 월, 미삭제)
        MonthlyQuotaPolicy.Window month = MonthlyQuotaPolicy.monthOf(Instant.now());
        long used = photocards.countByUserIdAndDeletedAtIsNullAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(userId, month.from(), month.to());
        if (!MonthlyQuotaPolicy.allows(used, perMonth)) {
            log.warn("[검증 실패 - 포토카드 월 제한] userId={}, used={}, limit={}", userId, used, perMonth);
            throw new ApplicationException(ErrorCode.PHOTOCARD_LIMIT);
        }

        // 2. 이미지 형식·크기
        ImageValidator.Format format = ImageValidator.validate(image, maxPx);

        // 3. 붙일 기록은 내 것이어야 한다. 남의 것·삭제된 것은 404 로 존재를 숨긴다
        if (linkedRecordId != null && !attendanceQuery.isOwnedBy(linkedRecordId, userId)) {
            throw new NotFoundException(ErrorCode.RECORD_NOT_FOUND);
        }

        // 4. 외부 호출: S3 업로드
        String key = "photocards/" + userId + "/" + UUID.randomUUID() + "." + format.extension();
        images.put(key, image, format.contentType());

        // 5. 저장
        Photocard saved = photocards.save(new Photocard(userId, linkedRecordId, key, stickers));
        log.info("[포토카드 생성] userId={}, photocardId={}, key={}, linkedRecordId={}, stickers={}", userId, saved.getId(), key,
                linkedRecordId, saved.getStickers().size());
        return PhotocardView.of(saved, images);
    }
}
