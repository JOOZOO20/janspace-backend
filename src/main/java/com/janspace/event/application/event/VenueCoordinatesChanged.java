package com.janspace.event.application.event;

/**
 * 공연장 좌표가 채워지거나(최초) 관리자에 의해 바뀌었다. attendance 가 그 공연장 모든 행사의 참석자 체인을 재계산한다.
 */
public record VenueCoordinatesChanged(Long venueId) {
}
