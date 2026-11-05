package com.stagelog.setlist.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 하루(태평양 날짜) Quota 사용 현황. bucket 별 한도·사용·예약과 행사별 사용 상위.
 */
public record QuotaUsage(LocalDate date, List<Bucket> buckets, List<EventUsage> topEvents) {

    public record Bucket(String bucket, int limitUnits, int usedUnits, int reservedUnits, Instant exhaustedAt) {
    }

    public record EventUsage(Long eventId, long units) {
    }
}
