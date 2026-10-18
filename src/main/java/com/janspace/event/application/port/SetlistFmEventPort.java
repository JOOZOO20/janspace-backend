package com.janspace.event.application.port;

import com.janspace.event.domain.EventSourcePolicy.SourceSnapshot;
import java.util.List;

/**
 * setlist.fm 아티스트 셋리 페이지 조회 (최신순, 1부터). 범위를 넘으면 빈 목록. 스냅샷은 externalId=setlist id, occurrenceKey=eventDate.
 */
public interface SetlistFmEventPort {

    boolean isEnabled();

    List<SourceSnapshot> page(int page);
}
