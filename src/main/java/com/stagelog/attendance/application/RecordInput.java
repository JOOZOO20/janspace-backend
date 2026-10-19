package com.stagelog.attendance.application;

import java.util.List;

/**
 * 기록 생성 입력 (프론트 RecordCreate). emotions 는 원문 코드라 검증은 유스케이스가 한다. departure 가 null 이면 프로필 값.
 */
public record RecordInput(Long eventId, List<String> emotions, Long songId, String memo, boolean retro,
                          boolean chainedFromPrevious, Departure departure) {

    public record Departure(String name, String countryCode, double lat, double lng) {
    }
}
