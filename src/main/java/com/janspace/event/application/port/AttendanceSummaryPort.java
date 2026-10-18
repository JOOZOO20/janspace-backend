package com.janspace.event.application.port;

import java.util.Collection;
import java.util.Map;

/**
 * 행사 응답에 별 수(starCount)·내 참석 상태(my)를 얹고 갈래요를 처리하기 위한 포트. attendance 기능이 구현한다.
 * event -> attendance 역방향 의존을 끊기 위한 포트이며 구현이 없으면(attendance 미탑재) 0 / null 로 둔다.
 */
public interface AttendanceSummaryPort {

    Map<Long, Long> starCounts(Collection<Long> eventIds);

    // 로그인 사용자의 행사별 상태. 행이 없는 행사는 맵에 없다 (NONE)
    Map<Long, MySummary> my(Long userId, Collection<Long> eventIds);

    MySummary plan(Long userId, Long eventId);

    MySummary unplan(Long userId, Long eventId);

    /** status 는 NONE / GOING / RECORDED, recordId 는 RECORDED 일 때 기록 ID. */
    record MySummary(String status, Long recordId) {
        public static final MySummary NONE = new MySummary("NONE", null);
    }
}
