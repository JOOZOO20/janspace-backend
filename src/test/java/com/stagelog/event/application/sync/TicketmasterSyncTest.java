package com.stagelog.event.application.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stagelog.event.application.EventCommand;
import com.stagelog.event.application.port.TicketmasterPort;
import com.stagelog.event.application.sync.SourceLinker.Kind;
import com.stagelog.event.application.sync.SourceLinker.Outcome;
import com.stagelog.event.application.sync.SyncRunner.SyncResult;
import com.stagelog.event.domain.EventSourcePolicy.SourceSnapshot;
import com.stagelog.event.domain.Provider;
import com.stagelog.ops.application.OperatorAlert;
import com.stagelog.ops.application.SyncRunRecorder;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TicketmasterSyncTest {

    static SourceSnapshot snapshot(String id) {
        LocalDate date = LocalDate.of(2026, 10, 23);
        return new SourceSnapshot(id, date.toString(), null, null, null, null, null, null, date, null);
    }

    @Test
    @DisplayName("항목 하나가 실패해도 뒤 항목은 처리하고, 실행은 실패 건수를 담아 FAILED 로 기록한다")
    void oneFailingItemDoesNotBlockTheRest() {
        TicketmasterPort port = mock(TicketmasterPort.class);
        SourceLinker linker = mock(SourceLinker.class);
        SourceSnapshot broken = snapshot("tm-1");
        SourceSnapshot ok = snapshot("tm-2");
        when(port.isEnabled()).thenReturn(true);
        when(port.artistEvents()).thenReturn(List.of(broken, ok));
        when(linker.link(Provider.TICKETMASTER, broken)).thenThrow(new IllegalStateException("깨진 항목"));
        when(linker.link(Provider.TICKETMASTER, ok)).thenReturn(new Outcome(Kind.DISCOVERED, null));
        SyncRunner runner = new SyncRunner(mock(SyncRunRecorder.class), mock(OperatorAlert.class), null, SyncRunnerTest.CAS);
        TicketmasterSync sync = new TicketmasterSync(port, linker, mock(EventCommand.class), runner, mock(OperatorAlert.class));

        SyncResult result = sync.sync();

        verify(linker).link(eq(Provider.TICKETMASTER), eq(ok));
        assertThat(result.failed()).isTrue();
        assertThat(result.summary()).contains("발견 1").contains("항목 실패 1");
    }
}
