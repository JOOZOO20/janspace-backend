package com.janspace.event.application.event;

/**
 * 행사 일정(UTC 시각·날짜·시간대·취소)이나 기록 알림 시각 추정에 쓰는 공연 시간(RunningTime)이 확정 변경됐다.
 * 같은 시즌 회차의 공연 시간을 빌려 쓰는 행사는 그 회차가 바뀔 때도 받는다. notification 이 수신한다.
 */
public record EventScheduleChanged(Long eventId) {
}
