package com.janspace.photocard.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PhotocardRepository extends JpaRepository<Photocard, Long> {

    Optional<Photocard> findByIdAndDeletedAtIsNull(Long id);

    List<Photocard> findAllByUserIdAndDeletedAtIsNullOrderByIdDesc(Long userId);

    List<Photocard> findAllByUserIdAndDeletedAtIsNull(Long userId);

    // 월 제한 계산: 미삭제이고 createdAt 이 [from, to) 인 장 수
    long countByUserIdAndDeletedAtIsNullAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(Long userId, Instant from, Instant to);

    // 기록 삭제 시 연결 해제용 (소프트 삭제된 포토카드도 함께 끊는다)
    List<Photocard> findAllByAttendanceId(Long attendanceId);

    // 기록 1 : 포토카드 1. attendance_id 유일 제약과 같은 범위(삭제된 카드 포함)를 본다
    boolean existsByAttendanceId(Long attendanceId);
}
