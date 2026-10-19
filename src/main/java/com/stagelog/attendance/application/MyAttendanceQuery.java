package com.stagelog.attendance.application;

import com.stagelog.attendance.application.AttendanceQuery.MyStatus;
import com.stagelog.attendance.domain.Attendance;
import com.stagelog.attendance.domain.AttendanceRepository;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 한 명의 행사별 참석 상태 전체 (다른 기능에 공개하는 계약). ticket 이 티켓-기록 연결과 인식 실패 시 대체 후보에 쓴다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MyAttendanceQuery {

    private final AttendanceRepository attendances;

    // 행사 ID -> 상태. 참석 표시(GOING)나 기록(RECORDED)이 있는 행사만, recordId 는 RECORDED 일 때만
    public Map<Long, MyStatus> statuses(Long userId) {
        Map<Long, MyStatus> out = new HashMap<>();
        for (Attendance row : attendances.findAllByUserIdAndDeletedAtIsNull(userId)) {
            MyStatus status = AttendanceQuery.status(row);
            if (status.status() != AttendanceQuery.Status.NONE) {
                out.put(row.getEventId(), status);
            }
        }
        return out;
    }
}
