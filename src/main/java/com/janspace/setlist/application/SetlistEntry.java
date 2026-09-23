package com.janspace.setlist.application;

/**
 * 다른 기능·API 에 공개하는 셋리 항목. position 은 1 부터.
 */
public record SetlistEntry(Long songId, int position, boolean inSetlistFm) {
}
