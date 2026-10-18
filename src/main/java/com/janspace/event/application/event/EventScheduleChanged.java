package com.janspace.event.application.event;

/**
 * 행사 일정(UTC 시각·날짜·시간대·취소)이 확정 변경됐다. 발행 지점은 설계. notification 이 수신한다.
 */
public record EventScheduleChanged(Long eventId) {
}
