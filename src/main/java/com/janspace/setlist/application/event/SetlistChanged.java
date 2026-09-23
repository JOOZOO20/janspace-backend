package com.janspace.setlist.application.event;

/**
 * 셋리 항목이 바뀔 때마다(편집·되돌리기·시드·병합·기록 추가) 발행. setlist 내부 리스너가 재생목록을 커밋 뒤 비동기로 맞춘다.
 */
public record SetlistChanged(Long eventId) {
}
