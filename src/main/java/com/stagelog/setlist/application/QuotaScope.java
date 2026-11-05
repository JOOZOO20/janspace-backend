package com.stagelog.setlist.application;

import com.stagelog.setlist.domain.QuotaBucket;

/**
 * YouTube 호출을 원장에 남길 때의 맥락. ceilingPercent 는 bucket 한도 중 이 호출이 쓸 수 있는 누적 상한(%)이다 (수동은 100).
 */
public record QuotaScope(QuotaBucket bucket, int ceilingPercent, Long eventId, Long syncRunId) {

    public static QuotaScope manual(Long eventId, Long syncRunId) {
        return new QuotaScope(QuotaBucket.MANUAL, 100, eventId, syncRunId);
    }
}
