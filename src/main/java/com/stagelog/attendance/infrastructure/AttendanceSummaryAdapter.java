package com.stagelog.attendance.infrastructure;

import com.stagelog.attendance.application.AttendanceCommand;
import com.stagelog.attendance.application.AttendanceQuery;
import com.stagelog.attendance.application.AttendanceQuery.MyStatus;
import com.stagelog.event.application.port.AttendanceSummaryPort;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * event 의 AttendanceSummaryPort 구현. 행사 응답의 starCount·my 와 갈래요를 attendance 유스케이스로 넘긴다.
 */
@Component
@RequiredArgsConstructor
public class AttendanceSummaryAdapter implements AttendanceSummaryPort {

    private final AttendanceQuery attendanceQuery;
    private final AttendanceCommand attendanceCommand;

    @Override
    public Map<Long, Long> starCounts(Collection<Long> eventIds) {
        return attendanceQuery.constellationCounts(eventIds);
    }

    @Override
    public Map<Long, MySummary> my(Long userId, Collection<Long> eventIds) {
        Map<Long, MySummary> out = new HashMap<>();
        attendanceQuery.my(userId, eventIds).forEach((eventId, status) -> out.put(eventId, toSummary(status)));
        return out;
    }

    @Override
    public MySummary plan(Long userId, Long eventId) {
        return toSummary(attendanceCommand.plan(userId, eventId));
    }

    @Override
    public MySummary unplan(Long userId, Long eventId) {
        return toSummary(attendanceCommand.unplan(userId, eventId));
    }

    private static MySummary toSummary(MyStatus status) {
        return new MySummary(status.status().name(), status.recordId());
    }
}
