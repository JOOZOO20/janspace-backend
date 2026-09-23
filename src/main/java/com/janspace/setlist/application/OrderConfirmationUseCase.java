package com.janspace.setlist.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 순서 확정 토글. merged_at 과 독립이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderConfirmationUseCase {

    private final SetlistWriter writer;

    @Transactional
    public void setConfirmed(Long eventId, boolean confirmed) {
        writer.pageOf(eventId).confirmOrder(confirmed);
        log.info("[셋리 순서 확정 토글] eventId={}, confirmed={}", eventId, confirmed);
    }
}
