package com.janspace.photocard.application;

import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.NotFoundException;
import com.janspace.photocard.domain.Photocard;
import com.janspace.photocard.domain.PhotocardRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 포토카드 삭제·정리: 사용자 삭제(소유자만, 아니면 404), 기록 삭제 시 연결 해제, 탈퇴 시 소프트 삭제.
 * S3 객체는 지우지 않는다(소프트 삭제 원칙, 키는 UUID 라 추측 불가).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class DeletePhotocardUseCase {

    private final PhotocardRepository photocards;

    public void delete(Long userId, Long photocardId) {
        Photocard card = photocards.findByIdAndDeletedAtIsNull(photocardId)
                .filter(c -> c.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundException(ErrorCode.PHOTOCARD_NOT_FOUND));
        card.softDelete();
        log.info("[포토카드 삭제] userId={}, photocardId={}", userId, photocardId);
    }

    // AttendanceDeleted 수신: 그 기록에 붙은 포토카드는 바인더에 남고 별 연결만 끊는다
    public void unlinkAttendance(Long attendanceId) {
        List<Photocard> linked = photocards.findAllByAttendanceId(attendanceId);
        linked.forEach(Photocard::unlink);
        log.info("[포토카드 연결 해제] attendanceId={}, rows={}", attendanceId, linked.size());
    }

    // UserWithdrawn 수신: 사용자의 모든 포토카드 소프트 삭제
    public void withdraw(Long userId) {
        List<Photocard> rows = photocards.findAllByUserIdAndDeletedAtIsNull(userId);
        rows.forEach(Photocard::softDelete);
        log.info("[탈퇴 포토카드 정리] userId={}, rows={}", userId, rows.size());
    }
}
