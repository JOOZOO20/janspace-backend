package com.stagelog.setlist.domain;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApiQuotaLedgerRepository extends JpaRepository<ApiQuotaLedger, Long> {

    interface EventUnits {
        Long getEventId();

        Long getUnits();
    }

    // RESERVED 인 행만 바꾼다. 영향 행 1 이면 호출자가 일일 행의 합계를 옮긴다 (같은 행을 두 번 확정·반환하지 않는다)
    @Modifying
    @Query(value = "UPDATE api_quota_ledger SET status = :status, updated_at = UTC_TIMESTAMP(6) WHERE id = :id AND status = 'RESERVED'",
            nativeQuery = true)
    int finish(@Param("id") Long id, @Param("status") String status);

    // 그날 행사별 사용량(CONSUMED + UNKNOWN) 상위. idx_date_event 를 쓴다
    @Query(value = "SELECT event_id AS eventId, CAST(SUM(units) AS SIGNED) AS units FROM api_quota_ledger "
            + "WHERE provider = :provider AND quota_date = :date AND event_id IS NOT NULL AND status IN ('CONSUMED', 'UNKNOWN') "
            + "GROUP BY event_id ORDER BY units DESC, event_id LIMIT :limit", nativeQuery = true)
    List<EventUnits> topEvents(@Param("provider") String provider, @Param("date") LocalDate date, @Param("limit") int limit);
}
