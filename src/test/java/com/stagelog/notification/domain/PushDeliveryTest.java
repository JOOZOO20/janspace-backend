package com.stagelog.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 배달 상태 전이: 백오프 1m/5m/30m, 3회째 실패 FAILED, 410 GONE, 400 FAILED, 고아 회수, 수동 재시도.
 */
class PushDeliveryTest {

    static final Instant T0 = Instant.parse("2026-10-23T14:10:00Z");

    @Test
    @DisplayName("백오프는 1분·5분·30분, 자동 시도는 3회까지")
    void backoff_policy() {
        assertThat(BackoffPolicy.delayAfter(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(BackoffPolicy.delayAfter(2)).isEqualTo(Duration.ofMinutes(5));
        assertThat(BackoffPolicy.delayAfter(3)).isEqualTo(Duration.ofMinutes(30));
        assertThat(BackoffPolicy.delayAfter(9)).isEqualTo(Duration.ofMinutes(30));
        assertThat(BackoffPolicy.canRetry(2)).isTrue();
        assertThat(BackoffPolicy.canRetry(3)).isFalse();
    }

    @Test
    @DisplayName("503 x2 는 RETRY(1분, 5분), 3회째 실패는 FAILED. 이후 수동 재시도 -> RETRY, attempts 유지")
    void retries_then_fails_after_third_attempt() {
        PushDelivery d = PushDelivery.pending(1L, 2L, 3L);

        d.markSending(T0);
        assertThat(d.complete(503, "HTTP 503", T0)).isEqualTo(PushDeliveryStatus.RETRY);
        assertThat(d.getAttempts()).isEqualTo(1);
        assertThat(d.getNextAttemptAt()).isEqualTo(T0.plus(Duration.ofMinutes(1)));

        Instant t1 = d.getNextAttemptAt();
        d.markSending(t1);
        assertThat(d.complete(0, "java.net.ConnectException", t1)).isEqualTo(PushDeliveryStatus.RETRY);
        assertThat(d.getAttempts()).isEqualTo(2);
        assertThat(d.getNextAttemptAt()).isEqualTo(t1.plus(Duration.ofMinutes(5)));

        Instant t2 = d.getNextAttemptAt();
        d.markSending(t2);
        assertThat(d.complete(429, null, t2)).isEqualTo(PushDeliveryStatus.FAILED);
        assertThat(d.getAttempts()).isEqualTo(3);
        assertThat(d.getLastError()).isEqualTo("HTTP 429");

        assertThat(d.retryManually(t2)).isTrue();
        assertThat(d.getStatus()).isEqualTo(PushDeliveryStatus.RETRY);
        assertThat(d.getAttempts()).isEqualTo(3);
        assertThat(d.getNextAttemptAt()).isEqualTo(t2);
        // 수동 1회 뒤 또 실패하면 attempts 가 상한을 넘어 바로 FAILED
        d.markSending(t2);
        assertThat(d.complete(500, null, t2)).isEqualTo(PushDeliveryStatus.FAILED);
        assertThat(d.getAttempts()).isEqualTo(4);
    }

    @Test
    @DisplayName("2xx SENT(sent_at), 410 GONE, 404 GONE, 400 FAILED. 수동 재시도는 FAILED 에서만")
    void terminal_outcomes() {
        PushDelivery sent = PushDelivery.pending(1L, 2L, 3L);
        sent.markSending(T0);
        assertThat(sent.complete(201, null, T0)).isEqualTo(PushDeliveryStatus.SENT);
        assertThat(sent.getSentAt()).isEqualTo(T0);
        assertThat(sent.retryManually(T0)).isFalse();

        PushDelivery gone = PushDelivery.pending(1L, 2L, 3L);
        gone.markSending(T0);
        assertThat(gone.complete(410, "HTTP 410 gone", T0)).isEqualTo(PushDeliveryStatus.GONE);

        PushDelivery notFound = PushDelivery.pending(1L, 2L, 3L);
        notFound.markSending(T0);
        assertThat(notFound.complete(404, null, T0)).isEqualTo(PushDeliveryStatus.GONE);

        PushDelivery bad = PushDelivery.pending(1L, 2L, 3L);
        bad.markSending(T0);
        assertThat(bad.complete(400, "x".repeat(600), T0)).isEqualTo(PushDeliveryStatus.FAILED);
        assertThat(bad.getLastError()).hasSize(PushDelivery.MAX_ERROR_LENGTH);
        assertThat(bad.getAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("고아 SENDING 회수: attempts<3 이면 RETRY, 아니면 FAILED(orphaned). SENDING 이 아니면 예외")
    void orphan_recovery() {
        PushDelivery young = PushDelivery.pending(1L, 2L, 3L);
        young.markSending(T0);
        assertThat(young.recoverOrphan(T0.plus(Duration.ofMinutes(6)))).isEqualTo(PushDeliveryStatus.RETRY);
        assertThat(young.getLastError()).isEqualTo(PushDelivery.ORPHANED);
        assertThat(young.getNextAttemptAt()).isEqualTo(T0.plus(Duration.ofMinutes(7)));

        PushDelivery exhausted = PushDelivery.pending(1L, 2L, 3L);
        exhausted.markSending(T0);
        exhausted.complete(503, null, T0);
        exhausted.markSending(T0);
        exhausted.complete(503, null, T0);
        exhausted.markSending(T0);
        assertThat(exhausted.getAttempts()).isEqualTo(3);
        assertThat(exhausted.recoverOrphan(T0)).isEqualTo(PushDeliveryStatus.FAILED);

        assertThatThrownBy(() -> exhausted.recoverOrphan(T0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> exhausted.complete(200, null, T0)).isInstanceOf(IllegalStateException.class);
    }
}
