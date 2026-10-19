package com.stagelog.catalog.application.port;

/**
 * 엑셀 한 행. 열 순서: 한국어 제목 | 영어 제목 | 중국어(번체) 제목 | 일본어 제목 | setlist.fm 표기(| 구분) | iTunes 곡 ID | 유튜브 영상 ID | 앨범명(한국어).
 * rowNumber 는 사람이 보는 1-based 행 번호.
 */
public record SongSheetRow(
        int rowNumber,
        String ko,
        String en,
        String zhTw,
        String ja,
        String aliases,
        String itunesTrackId,
        String youtubeVideoId,
        String albumTitle) {
}
