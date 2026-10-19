package com.stagelog.notification.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 잡 저장소. 선점·물질화·완료는 조건부 UPDATE 의 영향 행 수로 성공을 판정한다. 인스턴스가 여럿이어도 한 쪽만 이긴다.
 * 트랜잭션은 호출하는 유스케이스가 연다.
 */
public interface PushJobRepository extends JpaRepository<PushJob, Long> {

    Optional<PushJob> findByEventIdAndKind(Long eventId, PushKind kind);

    List<PushJob> findAllByEventId(Long eventId);

    List<PushJob> findAllByStatus(PushJobStatus status, Pageable pageable);

    List<PushJob> findAllByStatusAndDueAtLessThanEqual(PushJobStatus status, Instant now);

    List<PushJob> findAllByStatusAndMaterializedAtIsNull(PushJobStatus status);

    // 1단계 잡 선점. 영향 행 0 이면 다른 인스턴스가 먼저 가져갔거나 아직 due 가 아니다
    @Modifying
    @Query("""
            update PushJob j set j.status = :dispatching, j.claimedAt = :now
            where j.id = :id and j.status = :scheduled and j.dueAt <= :now""")
    int claim(@Param("id") Long id, @Param("now") Instant now,
              @Param("scheduled") PushJobStatus scheduled, @Param("dispatching") PushJobStatus dispatching);

    default int claim(Long id, Instant now) {
        return claim(id, now, PushJobStatus.SCHEDULED, PushJobStatus.DISPATCHING);
    }

    // 2단계 물질화 확정. 영향 행 0 이면 호출자가 트랜잭션을 롤백한다. 배달 INSERT 가 먼저 flush 되도록 한다
    @Modifying(flushAutomatically = true)
    @Query("update PushJob j set j.materializedAt = :now where j.id = :id and j.materializedAt is null")
    int markMaterialized(@Param("id") Long id, @Param("now") Instant now);

    // 5단계 완료. 물질화 전에는 절대 DONE 이 되지 않는다
    @Modifying
    @Query("update PushJob j set j.status = :done where j.id = :id and j.status = :dispatching")
    int markDone(@Param("id") Long id, @Param("dispatching") PushJobStatus dispatching, @Param("done") PushJobStatus done);

    default int markDone(Long id) {
        return markDone(id, PushJobStatus.DISPATCHING, PushJobStatus.DONE);
    }

    // 물질화됐고 열린 배달(PENDING/RETRY/SENDING)이 없는 DISPATCHING 잡
    @Query("""
            select j.id from PushJob j
            where j.status = :dispatching and j.materializedAt is not null
              and not exists (select d from PushDelivery d where d.jobId = j.id and d.status in :open)""")
    List<Long> findCompletableIds(@Param("dispatching") PushJobStatus dispatching, @Param("open") Collection<PushDeliveryStatus> open);

    default List<Long> findCompletableIds() {
        return findCompletableIds(PushJobStatus.DISPATCHING,
                List.of(PushDeliveryStatus.PENDING, PushDeliveryStatus.RETRY, PushDeliveryStatus.SENDING));
    }
}
