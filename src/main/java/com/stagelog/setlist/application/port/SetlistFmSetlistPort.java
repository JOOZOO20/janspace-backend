package com.stagelog.setlist.application.port;

import java.util.Optional;

/**
 * setlist.fm 셋리 조회 포트. 구현은 setlist/infrastructure (integration/setlistfm 클라이언트 사용). 없는 ID 는 empty.
 */
public interface SetlistFmSetlistPort {

    Optional<FmSetlist> fetch(String setlistId);
}
