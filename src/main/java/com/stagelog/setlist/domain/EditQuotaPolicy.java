package com.stagelog.setlist.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;

/**
 * 계정당 하루 편집 횟수 정책. 하루 경계는 UTC 날짜, 세는 대상은 FAN·REVERT 리비전. 한도는 설정값이다.
 */
public record EditQuotaPolicy(int limit) {

    public static final Set<RevisionSource> COUNTED = Set.of(RevisionSource.FAN, RevisionSource.REVERT);

    public boolean allows(long editsToday) {
        return editsToday < limit;
    }

    public static Instant utcDayStart(Instant now) {
        return LocalDate.ofInstant(now, ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    public static Instant utcDayEnd(Instant now) {
        return LocalDate.ofInstant(now, ZoneOffset.UTC).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
