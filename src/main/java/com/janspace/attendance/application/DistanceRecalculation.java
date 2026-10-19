package com.janspace.attendance.application;

import com.janspace.attendance.domain.Attendance;
import com.janspace.attendance.domain.AttendanceRepository;
import com.janspace.attendance.domain.ChainPolicy;
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

    // 갔어요 기록은 행사 순 체인, 갈래요만 있는 행은 단독 왕복.
    // 같은 사용자의 재계산 두 건이 겹치면 서로 상대가 쓴 행을 못 보고 덮어쓰므로(lost update) 행을 잠그고 읽는다.
    // 기록 쓰기는 AttendanceCommand 가 첫 문장에서 같은 잠금을 먼저 잡는다.
    // ponytail: 행 잠금이라 행이 없는 사용자의 첫 동시 쓰기, 여러 사용자를 도는 이벤트 재계산끼리는 교착할 수 있다(InnoDB 가 한쪽을 롤백:
    // 요청은 409, 리스너는 ERROR 로그). 덮어쓰기 대신 실패로 끝난다. 잦아지면 사용자 단위 GET_LOCK 으로 올린다
    @Transactional
    public void forUser(Long userId) {
        List<Attendance> rows = attendances.lockAllByUserId(userId);
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
        // 저장된 continued_from_previous 는 사용자의 선언으로 보존하고, 실제 연결은 지금 순서의 직전 기록으로 ChainPolicy 를 다시 판정한다.
        // 직전 기록이 지워지거나 소급 기록이 끼어들면 연결이 끊기고, 원래 직전이 돌아오면 다시 이어진다
        List<Stop> stops = new ArrayList<>(stars.size());
        for (int i = 0; i < stars.size(); i++) {
            Attendance row = stars.get(i);
            EventSnapshot event = events.get(row.getEventId());
            boolean continued = row.isContinuedFromPrevious() && i > 0
                    && ChainPolicy.eligible(AttendanceQuery.stop(events.get(stars.get(i - 1).getEventId())), AttendanceQuery.stop(event));
            stops.add(new Stop(home(row), venue(event), continued));
        }
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
