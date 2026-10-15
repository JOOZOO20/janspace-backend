package com.janspace.event.application.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.janspace.event.application.port.NaverCalendarPort;
import com.janspace.event.application.sync.SyncRunner.SyncResult;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NaverCalendarSyncTest {

    @Test
    @DisplayName("publishedKey 가 없으면 네이버를 호출하지 않고 건너뛴다 (빈 응답으로 기존 행사를 취소 의심으로 바꾸지 않게)")
    void skipsWithoutPublishedKey() {
        NaverCalendarPort port = mock(NaverCalendarPort.class);
        SyncRunner runner = mock(SyncRunner.class);
        when(port.isEnabled()).thenReturn(false);
        NaverCalendarSync sync = new NaverCalendarSync(port, null, null, null, null, runner, null);

        SyncResult result = sync.sync(LocalDate.of(2026, 10, 16));

        assertThat(result.ok()).isTrue();
        assertThat(result.summary()).contains("미설정");
        verify(port, never()).occurrences(any(), any());
        verify(runner, never()).run(any(), any());
    }
}
