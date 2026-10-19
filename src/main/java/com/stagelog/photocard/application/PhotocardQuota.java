package com.stagelog.photocard.application;

import java.time.Instant;

/**
 * 이번 달(UTC) 포토카드 제한. 카드와 네컷을 합산한다. resetsAt 은 다음 달 1일 00:00 UTC.
 */
public record PhotocardQuota(int limit, int remaining, Instant resetsAt) {
}
