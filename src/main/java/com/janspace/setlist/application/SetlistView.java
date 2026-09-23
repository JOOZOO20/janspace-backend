package com.janspace.setlist.application;

import java.time.Instant;
import java.util.List;

/**
 * 셋리 페이지 읽기 모델. 페이지가 아직 없는 행사는 version 0, 빈 목록, updatedAt null.
 */
public record SetlistView(Long eventId, int version, boolean orderConfirmed, List<SetlistEntry> entries, Instant updatedAt) {
}
