package com.janspace.attendance.application;

import com.janspace.attendance.domain.Attendance;
import com.janspace.attendance.domain.AttendanceRepository;
import com.janspace.attendance.domain.DistanceCalculator;
import com.janspace.attendance.domain.DistanceCalculator.Point;
import com.janspace.attendance.domain.DistanceCalculator.Stop;
import com.janspace.event.application.EventQuery;
import com.janspace.event.application.EventSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 한 명의 거리 체인 전체 재계산. 연속 이동이 앞 기록에 의존하므로 부분 갱신을 하지 않는다.
 * 호출자의 트랜잭션에 참여한다. 리스너에서 부를 때는 리스너가 새 트랜잭션을 연다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DistanceRecalculation {

    private final AttendanceRepository attendances;
    private final EventQuery eventQuery;

    // 갔어요 기록은 행사 순 체인, 갈래요만 있는 행은 단독 왕복
    @Transactional
    public void forUser(Long userId) {
        List<Attendance> rows = attendances.findAllByUserIdAndDeletedAtIsNull(userId);
        if (rows.isEmpty()) {
            return;
        }
        Map<Long, EventSnapshot> events = eventQuery.findByIds(rows.stream().map(Attendance::getEventId).toList()).stream()
                .collect(Collectors.toMap(EventSnapshot::id, Function.identity()));

        List<Attendance> stars = new ArrayList<>();
        for (Attendance row : rows) {
            EventSnapshot event = events.get(row.getEventId());
            if (row.isStar() && event != null) {
                stars.add(row);
            } else {
                row.updateDistance(DistanceCalculator.standalone(home(row), venue(event)));
            }
        }
        stars.sort((a, b) -> EventOrder.COMPARATOR.compare(events.get(a.getEventId()), events.get(b.getEventId())));
        List<Stop> stops = stars.stream()
                .map(row -> new Stop(home(row), venue(events.get(row.getEventId())), row.isContinuedFromPrevious()))
                .toList();
        List<Double> distances = DistanceCalculator.chain(stops);
        for (int i = 0; i < stars.size(); i++) {
            stars.get(i).updateDistance(distances.get(i));
        }
        log.info("[거리 체인 재계산] userId={}, rows={}, stars={}", userId, rows.size(), stars.size());
    }

    @Transactional
    public void forEvent(Long eventId) {
        attendances.findUserIdsByEventIds(List.of(eventId)).forEach(this::forUser);
    }

    @Transactional
    public void forVenue(Long venueId) {
        List<Long> eventIds = eventQuery.findByVenue(venueId).stream().map(EventSnapshot::id).toList();
        if (eventIds.isEmpty()) {
            return;
        }
        attendances.findUserIdsByEventIds(eventIds).forEach(this::forUser);
    }

    static Point home(Attendance row) {
        return row.getDeparture() == null ? null : row.getDeparture().point();
    }

    static Point venue(EventSnapshot event) {
        if (event == null || event.venue() == null || event.venue().lat() == null || event.venue().lng() == null) {
            return null;
        }
        return new Point(event.venue().lat(), event.venue().lng());
    }
}
