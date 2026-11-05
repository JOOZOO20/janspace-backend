package com.stagelog.setlist.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.io.Serializable;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * api_quota_daily 의 복합 PK (provider, quota_date, bucket).
 */
@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApiQuotaDailyId implements Serializable {

    @Column(nullable = false, length = 20)
    private String provider;

    @Column(name = "quota_date", nullable = false)
    private LocalDate quotaDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private QuotaBucket bucket;
}
