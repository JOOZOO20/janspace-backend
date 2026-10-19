package com.stagelog.photocard.application;

import com.stagelog.common.exception.ErrorCode;
import com.stagelog.common.exception.predefined.NotFoundException;
import com.stagelog.photocard.application.port.ImageStoragePort;
import com.stagelog.photocard.domain.Photocard;
import com.stagelog.photocard.domain.PhotocardRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 포토카드 삭제·정리: 사용자 삭제(소유자만, 아니면 404), 기록 삭제 시 연결 해제, 탈퇴 시 소프트 삭제.
 * 행은 소프트 삭제로 남기지만 이미지 객체는 사용자 사진이라 커밋 후 지운다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class DeletePhotocardUseCase {

    private final PhotocardRepository photocards;
    private final ImageStoragePort images;

    public void delete(Long userId, Long photocardId) {
        Photocard card = photocards.findByIdAndDeletedAtIsNull(photocardId)
                .filter(c -> c.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundException(ErrorCode.PHOTOCARD_NOT_FOUND));
        card.softDelete();
        StorageCleanup.afterCompletion(images, List.of(card.getImageKey()), List.of());
        log.info("[포토카드 삭제] userId={}, photocardId={}, key={}", userId, photocardId, card.getImageKey());
    }

    // AttendanceDeleted 수신: 그 기록에 붙은 포토카드는 바인더에 남고 별 연결만 끊는다
    public void unlinkAttendance(Long attendanceId) {
        List<Photocard> linked = photocards.findAllByAttendanceId(attendanceId);
        linked.forEach(Photocard::unlink);
        log.info("[포토카드 연결 해제] attendanceId={}, rows={}", attendanceId, linked.size());
    }

    // UserWithdrawn 수신: 사용자의 모든 포토카드 소프트 삭제 + 커밋 후 이미지 객체 전부 삭제
    public void withdraw(Long userId) {
        List<Photocard> rows = photocards.findAllByUserId(userId);
        rows.stream().filter(card -> !card.isDeleted()).forEach(Photocard::softDelete);
        StorageCleanup.afterCompletion(images, rows.stream().map(Photocard::getImageKey).toList(), List.of());
        log.info("[탈퇴 포토카드 정리] userId={}, rows={}", userId, rows.size());
    }
}
