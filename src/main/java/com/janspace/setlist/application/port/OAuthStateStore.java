package com.janspace.setlist.application.port;

/**
 * 유튜브 OAuth state 저장소 (Redis, TTL 10분). consume 은 원자적으로 읽고 지운다.
 */
public interface OAuthStateStore {

    void save(String state);

    boolean consume(String state);
}
