package com.janspace.attendance.application.event;

/**
 * 기록에서 최고의 곡이 정해졌다. setlist 가 수신해 그 행사 셋리 위키에 곡이 없으면 추가한다.
 */
public record BestSongChosen(Long eventId, Long songId, Long userId) {
}
