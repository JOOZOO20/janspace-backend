package com.janspace.setlist.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.janspace.catalog.application.CatalogQuery;
import com.janspace.common.exception.ApplicationException;
import com.janspace.setlist.domain.EditQuotaPolicy;
import com.janspace.setlist.domain.SetlistRevisionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 편집 한도의 하루 경계는 주입한 Clock 의 UTC 날짜다.
 */
class EditSetlistUseCaseTest {

    SetlistRevisionRepository revisions = mock(SetlistRevisionRepository.class);
    Clock clock = Clock.fixed(Instant.parse("2026-10-19T23:59:59Z"), ZoneOffset.UTC);
    EditSetlistUseCase useCase = new EditSetlistUseCase(mock(SetlistWriter.class), revisions, mock(CatalogQuery.class), 20, clock);

    @Test
    @DisplayName("Clock 기준 UTC 날짜 [00:00, 다음날 00:00) 의 FAN·REVERT 리비전이 한도에 차면 429")
    void quota_uses_injected_clock() {
        when(revisions.countByEditorUserIdAndSourceInAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                eq(1L), eq(EditQuotaPolicy.COUNTED), eq(Instant.parse("2026-10-19T00:00:00Z")), eq(Instant.parse("2026-10-20T00:00:00Z"))))
                .thenReturn(20L);

        assertThatThrownBy(() -> useCase.checkQuota(1L)).isInstanceOf(ApplicationException.class);
    }
}
