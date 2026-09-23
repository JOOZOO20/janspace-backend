package com.janspace.attendance.application;

import com.janspace.attendance.application.port.PhotocardLinkPort;
import com.janspace.attendance.domain.Attendance;
import com.janspace.attendance.domain.AttendanceRepository;
import com.janspace.attendance.domain.ChainPolicy;
import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.NotFoundException;
import com.janspace.event.application.EventQuery;
import com.janspace.event.application.EventSnapshot;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기록 조회. plannedUserIds/recordedWithoutBestSongUserIds/constellationCount(s)/isOwnedBy/my 는 다른 기능(notification, photocard,
 * event 포트)에 공개하는 계약, 나머지는 attendance API 용 읽기다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AttendanceQuery {

    private final AttendanceRepository attendances;
    private final EventQuery eventQuery;
    // photocard 가 구현하는 포트. 구현이 없으면 photocardId 는 항상 null (attendance -> photocard 의존 없음)
    private final ObjectProvider<PhotocardLinkPort> photocards;

    public enum Status {
        NONE, GOING, RECORDED
    }

    /** 로그인 사용자의 행사 참석 상태. recordId 는 RECORDED 일 때만. */
    public record MyStatus(Status status, Long recordId) {
    }

    /** 기록 + 그 시점 누적 거리(행사 순 합산) + 연결된 포토카드 ID(없으면 null). */
    public record Star(Attendance attendance, BigDecimal cumulativeKm, Long photocardId) {

        public Attendance.Brightness brightness() {
            return attendance.brightness(photocardId != null);
        }
    }

    public record ChainCheck(boolean eligible, Long previousEventId) {
    }

    public List<Long> plannedUserIds(Long eventId) {
        return attendances.findPlannedUserIds(eventId);
    }

    public List<Long> recordedWithoutBestSongUserIds(Long eventId) {
        return attendances.findRecordedWithoutBestSongUserIds(eventId);
    }

    public long constellationCount(Long eventId) {
        return constellationCounts(List.of(eventId)).getOrDefault(eventId, 0L);
    }

    public Map<Long, Long> constellationCounts(Collection<Long> eventIds) {
        return eventIds.isEmpty() ? Map.of() : toCounts(attendances.countStarsByEvent(eventIds));
    }

    // 기록이 1건 이상인 모든 행사의 별 수
    public Map<Long, Long> allConstellationCounts() {
        return toCounts(attendances.countStarsAll());
    }

    public boolean isOwnedBy(Long attendanceId, Long userId) {
        return attendances.findByIdAndDeletedAtIsNull(attendanceId).map(a -> a.getUserId().equals(userId)).orElse(false);
    }

    public Optional<MyStatus> my(Long userId, Long eventId) {
        return attendances.findByUserIdAndEventId(userId, eventId).filter(a -> !a.isDeleted()).map(AttendanceQuery::status);
    }

    public Map<Long, MyStatus> my(Long userId, Collection<Long> eventIds) {
        Map<Long, MyStatus> out = new HashMap<>();
        for (Attendance row : attendances.findAllByUserIdAndDeletedAtIsNull(userId)) {
            if (eventIds.contains(row.getEventId())) {
                out.put(row.getEventId(), status(row));
            }
        }
        return out;
    }

    // 내 기록(갔어요 이상) 을 행사 순으로. 누적 거리는 앞에서부터 더한다
    public List<Star> stars(Long userId) {
        List<Attendance> rows = attendances.findAllByUserIdAndDeletedAtIsNull(userId).stream().filter(Attendance::isStar).toList();
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, EventSnapshot> events = eventsOf(rows);
        List<Attendance> ordered = rows.stream().filter(r -> events.containsKey(r.getEventId()))
                .sorted((a, b) -> EventOrder.COMPARATOR.compare(events.get(a.getEventId()), events.get(b.getEventId())))
                .toList();
        Map<Long, Long> photocardIds = photocardIds(userId);
        List<Star> out = new ArrayList<>(ordered.size());
        BigDecimal sum = BigDecimal.ZERO;
        for (Attendance row : ordered) {
            if (row.getDistanceKm() != null) {
                sum = sum.add(row.getDistanceKm());
            }
            out.add(new Star(row, sum, photocardIds.get(row.getId())));
        }
        return out;
    }

    public Star star(Long userId, Long attendanceId) {
        return stars(userId).stream().filter(s -> s.attendance().getId().equals(attendanceId)).findFirst()
                .orElseThrow(() -> new NotFoundException(ErrorCode.RECORD_NOT_FOUND));
    }

    // 연속 이동 가능 여부: 이 행사 직전(행사 순)에 간 기록이 있고 ChainPolicy 를 만족하는가
    public ChainCheck canChain(Long userId, Long eventId) {
        EventSnapshot current = eventQuery.find(eventId).orElseThrow(() -> new NotFoundException(ErrorCode.EVENT_NOT_FOUND));
        return canChain(userId, current);
    }

    ChainCheck canChain(Long userId, EventSnapshot current) {
        List<Attendance> rows = attendances.findAllByUserIdAndDeletedAtIsNull(userId).stream()
                .filter(r -> r.isStar() && !r.getEventId().equals(current.id())).toList();
        Map<Long, EventSnapshot> events = eventsOf(rows);
        EventSnapshot previous = events.values().stream()
                .filter(e -> EventOrder.COMPARATOR.compare(e, current) < 0)
                .max(EventOrder.COMPARATOR)
                .orElse(null);
        boolean eligible = ChainPolicy.eligible(stop(previous), stop(current));
        return new ChainCheck(eligible, previous == null ? null : previous.id());
    }

    private Map<Long, Long> photocardIds(Long userId) {
        PhotocardLinkPort port = photocards.getIfAvailable();
        return port == null ? Map.of() : port.photocardIdsByAttendance(userId);
    }

    Map<Long, EventSnapshot> eventsOf(List<Attendance> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        return eventQuery.findByIds(rows.stream().map(Attendance::getEventId).distinct().toList()).stream()
                .collect(Collectors.toMap(EventSnapshot::id, Function.identity()));
    }

    static ChainPolicy.Stop stop(EventSnapshot event) {
        if (event == null) {
            return null;
        }
        EventSnapshot.VenueSnapshot venue = event.venue();
        boolean located = venue != null && venue.lat() != null && venue.lng() != null;
        return new ChainPolicy.Stop(event.eventDate(), venue == null ? null : venue.city(), located);
    }

    static MyStatus status(Attendance row) {
        if (row.isStar()) {
            return new MyStatus(Status.RECORDED, row.getId());
        }
        return new MyStatus(row.getPlannedAt() != null ? Status.GOING : Status.NONE, null);
    }

    private static Map<Long, Long> toCounts(List<Object[]> rows) {
        Map<Long, Long> out = new HashMap<>();
        rows.forEach(r -> out.put((Long) r[0], (Long) r[1]));
        return out;
    }
}
