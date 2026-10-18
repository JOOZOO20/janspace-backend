package com.janspace.event.application.sync;

import com.janspace.common.locale.SupportedLocale;
import com.janspace.event.application.EventCommand;
import com.janspace.event.application.port.TicketmasterPort;
import com.janspace.event.application.sync.SourceLinker.Kind;
import com.janspace.event.application.sync.SourceLinker.Outcome;
import com.janspace.event.application.sync.SyncRunner.SyncResult;
import com.janspace.event.domain.EventSourcePolicy.SourceSnapshot;
import com.janspace.event.domain.Provider;
import com.janspace.event.domain.TranslationField;
import com.janspace.ops.application.OperatorAlert;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Ticketmaster 동기화. 아티스트 이벤트마다 같은 날짜·국가의 내부 행사에 연결(공연장·좌표·시간대·시각·예매 URL·포스터 보강,
 * 영문 이름은 EN 번역), 없으면 발견 행.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketmasterSync {

    private final TicketmasterPort port;
    private final SourceLinker linker;
    private final EventCommand eventCommand;
    private final SyncRunner runner;
    private final OperatorAlert operatorAlert;

    public SyncResult sync() {
        if (!port.isEnabled()) {
            return SyncResult.skipped(Provider.TICKETMASTER.name(), "TICKETMASTER_API_KEY 미설정, 건너뜀");
        }
        return runner.run(Provider.TICKETMASTER.name(), this::doSync);
    }

    private String doSync() {
        List<SourceSnapshot> events = port.artistEvents();
        Map<Kind, Integer> counts = new EnumMap<>(Kind.class);
        int failed = 0;
        for (SourceSnapshot snapshot : events) {
            // 항목 하나의 처리 실패가 뒤 항목을 매번 막지 않게 건너뛰고, 실행은 끝에서 FAILED 로 기록한다
            try {
                Outcome outcome = linker.link(Provider.TICKETMASTER, snapshot);
                counts.merge(outcome.kind(), 1, Integer::sum);
                if ((outcome.kind() == Kind.LINKED || outcome.kind() == Kind.REFRESHED) && snapshot.name() != null) {
                    eventCommand.upsertProviderTranslation(outcome.eventId(), TranslationField.NAME, SupportedLocale.EN, snapshot.name());
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("[Ticketmaster 항목 실패] externalId={}, key={}", snapshot.externalId(), snapshot.occurrenceKey(), e);
            }
        }
        String summary = "Ticketmaster 이벤트 " + events.size() + "건: 연결 " + counts.getOrDefault(Kind.LINKED, 0)
                + ", 갱신 " + counts.getOrDefault(Kind.REFRESHED, 0) + ", 이동 제안 " + counts.getOrDefault(Kind.MOVE_PROPOSED, 0)
                + ", 발견 " + counts.getOrDefault(Kind.DISCOVERED, 0);
        if (failed > 0) {
            throw new IllegalStateException(summary + ", 항목 실패 " + failed);
        }
        log.info("[Ticketmaster 동기화] summary={}", summary);
        if (counts.getOrDefault(Kind.LINKED, 0) > 0 || counts.getOrDefault(Kind.DISCOVERED, 0) > 0) {
            operatorAlert.send("[Ticketmaster 동기화] " + summary);
        }
        return summary;
    }
}
