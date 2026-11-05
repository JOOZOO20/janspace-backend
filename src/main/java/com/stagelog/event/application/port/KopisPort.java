package com.stagelog.event.application.port;

import com.stagelog.event.domain.EventSourcePolicy.SourceSnapshot;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * KOPIS 조회. 목록 검색 -> 특정 날짜의 회차 스냅샷(시설 좌표·예매처·시각 포함) -> 포스터 다운로드.
 */
public interface KopisPort {

    boolean isEnabled();

    /** 공연명 검색어로 쓰는 아티스트 이름 (설정값). */
    String artistKeyword();

    /** 공연목록 한 건. 기간은 prfpdfrom~prfpdto. */
    record Listing(String mt20id, String name, LocalDate from, LocalDate to) {
        public boolean covers(LocalDate date) {
            return from != null && to != null && !date.isBefore(from) && !date.isAfter(to);
        }
    }

    /** 포스터 바이트와 Content-Type. */
    record Poster(byte[] bytes, String contentType) {
    }

    // [from, to] 는 31일 이하. afterDate 는 증분 기준일(null 이면 전체)
    List<Listing> search(LocalDate from, LocalDate to, String name, LocalDate afterDate);

    // 그 날짜의 회차 스냅샷. dtguidance 에 그 요일 시각이 여럿이면 시각마다 하나(키 'YYYY-MM-DD#HH:mm'), 없으면 날짜 키 하나
    List<SourceSnapshot> snapshots(String mt20id, LocalDate date);

    // 없는 포스터(404·410)·빈 본문은 empty, 일시 오류(5xx·연결)는 RuntimeException
    Optional<Poster> downloadPoster(String url);
}
