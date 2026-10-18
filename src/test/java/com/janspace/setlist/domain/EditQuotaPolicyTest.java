package com.janspace.setlist.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M4: N 번째까지 허용, N 번 채우면 거부. REVERT 도 세는 대상. 하루 경계는 UTC.
 */
class EditQuotaPolicyTest {

    @Test
    @DisplayName("한도 20: 19회 했으면 허용, 20회 했으면 거부")
    void n_minus_one_allowed_n_rejected() {
        EditQuotaPolicy policy = new EditQuotaPolicy(20);

        assertThat(policy.allows(19)).isTrue();
        assertThat(policy.allows(20)).isFalse();
    }

    @Test
    @DisplayName("FAN 과 REVERT 만 센다")
    void counted_sources() {
        assertThat(EditQuotaPolicy.COUNTED).containsExactlyInAnyOrder(RevisionSource.FAN, RevisionSource.REVERT);
    }

    @Test
    @DisplayName("하루 경계는 UTC 00:00 (KST 자정이 아니다)")
    void utc_day_bounds() {
        Instant now = Instant.parse("2026-10-23T23:30:00Z");

        assertThat(EditQuotaPolicy.utcDayStart(now)).isEqualTo(Instant.parse("2026-10-23T00:00:00Z"));
        assertThat(EditQuotaPolicy.utcDayEnd(now)).isEqualTo(Instant.parse("2026-10-24T00:00:00Z"));
    }
}
