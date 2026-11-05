package com.stagelog.setlist.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 태평양 날짜·bucket 하나의 Quota 한도와 사용·예약 합계. 값은 ApiQuotaDailyRepository 의 조건부 UPDATE 로만 바꾼다 (엔티티는 조회용).
 * 생성·수정 시각을 따로 두지 않는다(명세 스키마). exhaustedAt 이 시각 정보다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "api_quota_daily")
public class ApiQuotaDaily {

    @EmbeddedId
    private ApiQuotaDailyId id;

    @Column(name = "limit_units", nullable = false)
    private int limitUnits;

    @Column(name = "used_units", nullable = false)
    private int usedUnits;

    @Column(name = "reserved_units", nullable = false)
    private int reservedUnits;

    @Column(name = "exhausted_at", columnDefinition = "DATETIME(6)")
    private Instant exhaustedAt;
}
