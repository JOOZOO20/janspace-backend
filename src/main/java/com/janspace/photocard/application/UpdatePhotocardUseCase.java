package com.janspace.photocard.application;

import com.janspace.attendance.application.AttendanceQuery;
import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.ConflictException;
import com.janspace.common.exception.predefined.NotFoundException;
import com.janspace.photocard.application.port.ImageStoragePort;
import com.janspace.photocard.domain.Photocard;
import com.janspace.photocard.domain.PhotocardRepository;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 포토카드 부분 수정: 기록(별) 연결 바꾸기·끊기. 이미지와 그 메타(종류·배경색·프레임·스티커)는 합성 결과와 묶여 있어 바꾸지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UpdatePhotocardUseCase {

    private final PhotocardRepository photocards;
    private final AttendanceQuery attendanceQuery;
    private final ImageStoragePort images;

    // linkedRecordId: null = 키 없음(유지), empty = 연결 끊기, 값 = 그 기록으로 옮기기
    @Transactional
    public PhotocardView relink(Long userId, Long photocardId, Optional<Long> linkedRecordId) {
        Photocard card = photocards.findByIdAndDeletedAtIsNull(photocardId)
                .filter(c -> c.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundException(ErrorCode.PHOTOCARD_NOT_FOUND));
        if (linkedRecordId == null) {
            return PhotocardView.of(card, images);
        }

        Long before = card.getAttendanceId();
        Long target = linkedRecordId.orElse(null);
        // 케이스 A: 끊기
        if (target == null) {
            card.unlink();
        // 케이스 B: 다른 기록으로. 같은 기록이면 그대로 둔다
        } else if (!target.equals(before)) {
            if (!attendanceQuery.isOwnedBy(target, userId)) {
                throw new NotFoundException(ErrorCode.RECORD_NOT_FOUND);
            }
            if (photocards.existsByAttendanceId(target)) {
                throw new ConflictException(ErrorCode.PHOTOCARD_ALREADY_LINKED);
            }
            card.linkTo(target);
        }
        try {
            photocards.saveAndFlush(card);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException(ErrorCode.PHOTOCARD_ALREADY_LINKED);
        }
        if (!Objects.equals(before, card.getAttendanceId())) {
            log.info("[포토카드 연결 변경] userId={}, photocardId={}, before={}, after={}", userId, photocardId, before, card.getAttendanceId());
        }
        return PhotocardView.of(card, images);
    }
}
