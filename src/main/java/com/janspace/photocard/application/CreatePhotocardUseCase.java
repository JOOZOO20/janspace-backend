package com.janspace.photocard.application;

import com.janspace.attendance.application.AttendanceQuery;
import com.janspace.common.exception.ApplicationException;
import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.BadRequestException;
import com.janspace.common.exception.predefined.ConflictException;
import com.janspace.common.exception.predefined.NotFoundException;
import com.janspace.photocard.application.port.ImageStoragePort;
import com.janspace.photocard.domain.FrameRepository;
import com.janspace.photocard.domain.ImageValidator;
import com.janspace.photocard.domain.Photocard;
import com.janspace.photocard.domain.PhotocardKind;
import com.janspace.photocard.domain.PhotocardRepository;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 포토카드(카드·네컷) 생성. 1) 월 제한 2) 이미지 검증 3) 프레임 4) 기록 연결 5) S3 저장 6) 행 저장.
 */
@Slf4j
@Service
public class CreatePhotocardUseCase {

    private final PhotocardRepository photocards;
    private final FrameRepository frames;
    private final ImageStoragePort images;
    private final AttendanceQuery attendanceQuery;
    private final PhotocardQuery photocardQuery;
    private final int cardMaxPx;
    private final int fourcutMaxPx;

    public CreatePhotocardUseCase(PhotocardRepository photocards, FrameRepository frames, ImageStoragePort images,
                                  AttendanceQuery attendanceQuery, PhotocardQuery photocardQuery,
                                  @Value("${janspace.photocard.max-px:1350}") int cardMaxPx,
                                  @Value("${janspace.photocard.fourcut-max-px:3240}") int fourcutMaxPx) {
        this.photocards = photocards;
        this.frames = frames;
        this.images = images;
        this.attendanceQuery = attendanceQuery;
        this.photocardQuery = photocardQuery;
        this.cardMaxPx = cardMaxPx;
        this.fourcutMaxPx = fourcutMaxPx;
    }

    @Transactional
    public PhotocardView create(Long userId, byte[] image, CreatePhotocard cmd) {
        PhotocardKind kind = cmd.kind() == null ? PhotocardKind.CARD : cmd.kind();

        // 1. 월 제한 (UTC 월, 미삭제, 카드·네컷 합산)
        // ponytail: 세고 나서 넣으므로 같은 사용자의 동시 요청은 한도를 그 수만큼 넘을 수 있다(설계 §4.6). 남용 방지용 한도라 둔다.
        // 정확히 막아야 하면 이 트랜잭션 첫 문장에서 GET_LOCK('photocard:' + userId) 로 줄 세운다
        PhotocardQuota quota = photocardQuery.quota(userId);
        if (quota.remaining() == 0) {
            log.warn("[검증 실패 - 포토카드 월 제한] userId={}, limit={}", userId, quota.limit());
            throw new ApplicationException(ErrorCode.PHOTOCARD_LIMIT);
        }

        // 2. 이미지 형식·크기·비율 (카드 4:5 긴 변 1350px, 네컷 1:3 긴 변 3240px 기본값)
        ImageValidator.Image checked = ImageValidator.inspect(image, kind.maxBytes(), kind == PhotocardKind.FOURCUT ? fourcutMaxPx : cardMaxPx);
        if (!kind.matchesRatio(checked.width(), checked.height())) {
            throw new BadRequestException(ErrorCode.PHOTOCARD_IMAGE_INVALID,
                    kind + " 이미지는 " + kind.ratioLabel() + " 비율이어야 합니다. (받은 크기 " + checked.width() + "×" + checked.height() + ")");
        }

        // 3. 프레임은 네컷에만. 삭제된 프레임도 받는다(편집 중에 내려간 프레임 때문에 완성본을 잃지 않게)
        if (cmd.frameId() != null) {
            if (kind != PhotocardKind.FOURCUT) {
                throw new BadRequestException(ErrorCode.INVALID_INPUT, "frameId 는 FOURCUT 에만 넣을 수 있습니다.");
            }
            if (!frames.existsById(cmd.frameId())) {
                throw new NotFoundException(ErrorCode.FRAME_NOT_FOUND);
            }
        }

        // 4. 붙일 기록은 내 것이어야 하고(남의 것·삭제된 것은 404 로 존재를 숨긴다) 다른 카드가 붙어 있지 않아야 한다
        Long recordId = cmd.linkedRecordId();
        if (recordId != null) {
            if (!attendanceQuery.isOwnedBy(recordId, userId)) {
                throw new NotFoundException(ErrorCode.RECORD_NOT_FOUND);
            }
            if (photocards.existsByAttendanceId(recordId)) {
                throw new ConflictException(ErrorCode.PHOTOCARD_ALREADY_LINKED);
            }
        }

        // 5. 외부 호출: S3 업로드. 이후 저장이 롤백되면 올린 객체를 지운다
        String key = "photocards/" + userId + "/" + UUID.randomUUID() + "." + checked.format().extension();
        StorageCleanup.afterCompletion(images, List.of(), List.of(key));
        images.put(key, image, checked.format().contentType());

        // 6. 저장. 같은 기록에 동시에 두 장이 들어오면 유일 제약이 막는다
        Photocard saved;
        try {
            saved = photocards.saveAndFlush(new Photocard(userId, recordId, key, kind, cmd.backgroundColor(), cmd.frameId(), cmd.stickers()));
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException(ErrorCode.PHOTOCARD_ALREADY_LINKED);
        }
        log.info("[포토카드 생성] userId={}, photocardId={}, kind={}, key={}, linkedRecordId={}, frameId={}, stickers={}", userId, saved.getId(),
                kind, key, recordId, cmd.frameId(), saved.getStickers().size());
        return PhotocardView.of(saved, images);
    }
}
