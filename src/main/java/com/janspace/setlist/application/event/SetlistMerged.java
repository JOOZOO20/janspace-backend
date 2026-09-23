package com.janspace.setlist.application.event;

/**
 * setlist.fm 병합으로 셋리에 변화가 생겼을 때 발행. notification 이 SETLIST_CONFIRMED 잡을 만든다.
 */
public record SetlistMerged(Long eventId) {
}
