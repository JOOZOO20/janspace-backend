package com.janspace.ops.application.port;

/**
 * 운영자 알림 발송 포트. 구현은 ops/infrastructure (현재 디스코드 웹훅).
 */
public interface AlertPort {

    // 실패하면 RuntimeException 을 던진다. 삼키는 것은 OperatorAlert 의 몫
    void send(String message);
}
