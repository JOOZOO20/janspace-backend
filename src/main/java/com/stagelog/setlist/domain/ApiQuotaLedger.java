package com.stagelog.setlist.domain;

import com.stagelog.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Quota 원장 한 행 = 외부 API 호출(예정) 한 번. RESERVED 로 만들고 상태 전이는 ApiQuotaLedgerRepository.finish 의 조건부 UPDATE 로 한 번만 한다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "api_quota_ledger")
public class ApiQuotaLedger extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String provider;

    @Column(name = "quota_date", nullable = false)
    private LocalDate quotaDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private QuotaBucket bucket;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private QuotaOperation operation;

    @Column(nullable = false)
    private int units;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private QuotaEntryStatus status;

    @Column(name = "event_id")
    private Long eventId;

    @Column(name = "sync_run_id")
    private Long syncRunId;

    public static ApiQuotaLedger reserve(String provider, LocalDate quotaDate, QuotaBucket bucket, QuotaOperation operation,
                                         Long eventId, Long syncRunId) {
        ApiQuotaLedger entry = new ApiQuotaLedger();
        entry.provider = provider;
        entry.quotaDate = quotaDate;
        entry.bucket = bucket;
        entry.operation = operation;
        entry.units = operation.getUnits();
        entry.status = QuotaEntryStatus.RESERVED;
        entry.eventId = eventId;
        entry.syncRunId = syncRunId;
        return entry;
    }
}
