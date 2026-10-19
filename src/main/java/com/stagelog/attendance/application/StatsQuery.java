package com.stagelog.attendance.application;

import com.stagelog.attendance.domain.AltitudeScale;
import com.stagelog.attendance.domain.AltitudeScale.Headline;
import com.stagelog.attendance.domain.Attendance;
import com.stagelog.attendance.domain.AttendanceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내 통계: 누적 거리·기록 수·마지막 기록 시각·높이 헤드라인. 순위·백분위·전체 합산은 만들지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StatsQuery {

    private final AttendanceRepository attendances;

    public record Stats(BigDecimal totalKm, long recordCount, Instant lastRecordAt, Headline altitude) {
    }

    public Stats of(Long userId) {
        List<Attendance> stars = attendances.findAllByUserIdAndDeletedAtIsNull(userId).stream().filter(Attendance::isStar).toList();
        BigDecimal total = stars.stream().map(Attendance::getDistanceKm).filter(d -> d != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Instant last = stars.stream().map(Attendance::getAttendedAt).max(Comparator.naturalOrder()).orElse(null);
        return new Stats(total, stars.size(), last, AltitudeScale.headline(total.doubleValue()));
    }
}
