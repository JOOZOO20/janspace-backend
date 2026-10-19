package com.stagelog.account.application.port;

/**
 * authorize 요청의 목적. LOGIN 은 사용자 조회·생성, LINK 는 로그인한 사용자에게 계정을 연결한다.
 */
public enum LoginMode {
    LOGIN,
    LINK
}
