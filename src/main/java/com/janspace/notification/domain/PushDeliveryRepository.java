package com.janspace.notification.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 배달 저장소. 선점과 취소는 조건부 UPDATE 로만 한다. 트랜잭션은 호출하는 유스케이스가 연다.
 */
public interface PushDeliveryRepository extends JpaRepository<PushDelivery, Long> {

    List<PushDelivery> findAllByJobId(Long jobId);

    List<PushDelivery> findAllByStatusAndSendingStartedAtBefore(PushDeliveryStatus status, Instant threshold);

    // 3단계 대상: 물질화된 DISPATCHING 잡의 PENDING/RETRY 배달 중 재시도 시각이 지난 것
    @Query("""
            select d from PushDelivery d
            where d.status in :claimable and (d.nextAttemptAt is null or d.nextAttemptAt <= :now)
              and d.jobId in (select j.id from PushJob j where j.status = :dispatching and j.materializedAt is not null)
            order by d.jobId, d.id""")
    List<PushDelivery> findDue(@Param("now") Instant now, @Param("claimable") Collection<PushDeliveryStatus> claimable,
                               @Param("dispatching") PushJobStatus dispatching);

    default List<PushDelivery> findDue(Instant now) {
        return findDue(now, CLAIMABLE, PushJobStatus.DISPATCHING);
    }

    // 3단계 배달 선점 (attempts 소모). 영향 행 0 이면 다른 워커가 먼저 가져갔다
    @Modifying
    @Query("""
            update PushDelivery d set d.status = :sending, d.sendingStartedAt = :now, d.attempts = d.attempts + 1
            where d.id = :id and d.status in :claimable""")
    int claim(@Param("id") Long id, @Param("now") Instant now, @Param("claimable") Collection<PushDeliveryStatus> claimable,
              @Param("sending") PushDeliveryStatus sending);

    default int claim(Long id, Instant now) {
        return claim(id, now, CLAIMABLE, PushDeliveryStatus.SENDING);
    }

    // 미전송(PENDING/RETRY)만 취소. SENDING·SENT 는 건드리지 않는다
    @Modifying
    @Query("update PushDelivery d set d.status = :cancelled where d.jobId in :jobIds and d.status in :claimable")
    int cancelUnsentByJobIds(@Param("jobIds") Collection<Long> jobIds, @Param("claimable") Collection<PushDeliveryStatus> claimable,
                             @Param("cancelled") PushDeliveryStatus cancelled);

    default int cancelUnsentByJobIds(Collection<Long> jobIds) {
        return jobIds.isEmpty() ? 0 : cancelUnsentByJobIds(jobIds, CLAIMABLE, PushDeliveryStatus.CANCELLED);
    }

    @Modifying
    @Query("update PushDelivery d set d.status = :cancelled where d.userId = :userId and d.status in :claimable")
    int cancelUnsentByUserId(@Param("userId") Long userId, @Param("claimable") Collection<PushDeliveryStatus> claimable,
                             @Param("cancelled") PushDeliveryStatus cancelled);

    default int cancelUnsentByUserId(Long userId) {
        return cancelUnsentByUserId(userId, CLAIMABLE, PushDeliveryStatus.CANCELLED);
    }

    // 구독 해지·소유자 변경: 그 구독으로 아직 나가지 않은 배달만 취소
    @Modifying
    @Query("update PushDelivery d set d.status = :cancelled where d.subscriptionId = :subscriptionId and d.status in :claimable")
    int cancelUnsentBySubscriptionId(@Param("subscriptionId") Long subscriptionId,
                                     @Param("claimable") Collection<PushDeliveryStatus> claimable,
                                     @Param("cancelled") PushDeliveryStatus cancelled);

    default int cancelUnsentBySubscriptionId(Long subscriptionId) {
        return cancelUnsentBySubscriptionId(subscriptionId, CLAIMABLE, PushDeliveryStatus.CANCELLED);
    }

    List<PushDeliveryStatus> CLAIMABLE = List.of(PushDeliveryStatus.PENDING, PushDeliveryStatus.RETRY);
}
