package com.janspace.notification.application;

import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.ConflictException;
import com.janspace.common.exception.predefined.NotFoundException;
import com.janspace.notification.domain.PushDelivery;
import com.janspace.notification.domain.PushJob;
import com.janspace.notification.domain.PushDeliveryRepository;
import com.janspace.notification.domain.PushJobRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 수동 재시도. FAILED -> RETRY, attempts 는 유지한다. 잡이 DONE 이면 DISPATCHING 으로 되돌려 다음 사이클이 보내게 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AdminPushCommand {

    private final PushDeliveryRepository deliveries;
    private final PushJobRepository jobs;

    public PushDelivery retryDelivery(Long deliveryId) {
        PushDelivery delivery = deliveries.findById(deliveryId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.PUSH_DELIVERY_NOT_FOUND));
        if (!delivery.retryManually(Instant.now())) {
            throw new ConflictException(ErrorCode.PUSH_DELIVERY_NOT_RETRYABLE);
        }
        jobs.findById(delivery.getJobId()).ifPresent(PushJob::reopen);
        log.info("[푸시 배달 수동 재시도] deliveryId={}, jobId={}, attempts={}", deliveryId, delivery.getJobId(), delivery.getAttempts());
        return delivery;
    }
}
