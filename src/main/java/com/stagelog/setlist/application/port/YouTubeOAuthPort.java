package com.stagelog.setlist.application.port;

/**
 * Stagelog 유튜브 채널 OAuth 포트 (인가 URL, 코드 -> 리프레시 토큰 교환).
 */
public interface YouTubeOAuthPort {

    String authorizeUrl(String state);

    // 인가 코드를 리프레시 토큰으로 바꾼다. 실패는 502 YOUTUBE_API_ERROR
    String exchangeCode(String code);
}
