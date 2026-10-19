package com.stagelog.event.application.event;

/**
 * 행사의 공연장이 확정·변경됐다. attendance 가 그 행사 참석자의 거리 체인을 재계산한다.
 */
public record EventVenueChanged(Long eventId) {
}
