package com.stagelog.notification.application;

import com.stagelog.common.exception.ErrorCode;
import com.stagelog.common.exception.predefined.ConflictException;
import com.stagelog.common.exception.predefined.NotFoundException;
import com.stagelog.notification.domain.PushDelivery;
import com.stagelog.notification.domain.PushJob;
import com.stagelog.notification.domain.PushDeliveryRepository;
import com.stagelog.notification.domain.PushJobRepository;
import com.stagelog.ops.application.AdminAuditRecorder;
import com.stagelog.ops.application.AuditAction;
import com.stagelog.ops.application.AuditActor;
import java.time.Instant;
import java.util.Map;
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
    private final AdminAuditRecorder audit;

    public PushDelivery retryDelivery(Long deliveryId, AuditActor actor) {
        PushDelivery delivery = deliveries.findById(deliveryId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.PUSH_DELIVERY_NOT_FOUND));
        if (!delivery.retryManually(Instant.now())) {
            throw new ConflictException(ErrorCode.PUSH_DELIVERY_NOT_RETRYABLE);
        }
        jobs.findById(delivery.getJobId()).ifPresent(PushJob::reopen);
        audit.record(actor, AuditAction.PUSH_DELIVERY_RETRY, "PUSH_DELIVERY", String.valueOf(deliveryId),
                Map.of("status", "FAILED"), Map.of("status", delivery.getStatus().name()), null);
        log.info("[푸시 배달 수동 재시도] deliveryId={}, jobId={}, attempts={}", deliveryId, delivery.getJobId(), delivery.getAttempts());
        return delivery;
    }
}
