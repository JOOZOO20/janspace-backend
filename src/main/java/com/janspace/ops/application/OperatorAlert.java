package com.janspace.ops.application;

import com.janspace.ops.application.port.AlertPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 운영자 알림 공개 계약. 다른 기능은 이 클래스만 호출한다. 알림 실패가 호출자의 업무(동기화·병합)를 깨뜨리면 안 되므로 절대 예외를 밖으로 내지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperatorAlert {

    private final AlertPort alertPort;

    public void send(String message) {
        try {
            alertPort.send(message);
        } catch (RuntimeException e) {
            // 알림은 부가 기능. 실패는 로그로만 남기고 호출자는 계속 진행한다
            log.warn("[운영자 알림 실패] 알림을 보내지 못했습니다. length={}", message == null ? 0 : message.length(), e);
        }
    }
}
