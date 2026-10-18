package com.janspace.event.application.sync;

import com.janspace.event.application.ApplySourcesUseCase;
import com.janspace.event.application.ApplySourcesUseCase.Reconciliation;
import com.janspace.event.domain.Event;
import com.janspace.event.domain.EventRepository;
import com.janspace.event.domain.EventSource;
import com.janspace.event.domain.EventSourcePolicy.SourceSnapshot;
import com.janspace.event.domain.EventSourceRepository;
import com.janspace.event.domain.Provider;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회차 단위 외부 항목(Ticketmaster·setlist.fm)을 내부 행사에 잇는다.
 * 이미 연결된 외부 ID 는 회차 키 비교(이동·취소 의심·발견) 후 스냅샷 갱신, 처음 보는 ID 는 같은 현지 날짜·국가의 행사가
 * 정확히 하나일 때만 연결하고 아니면 발견 행(reconcileOccurrences 의 나타난 키 처리)으로 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SourceLinker {

    private final EventRepository eventRepository;
    private final EventSourceRepository sourceRepository;
    private final ApplySourcesUseCase applySources;

    public enum Kind {
        LINKED, REFRESHED, MOVE_PROPOSED, DISCOVERED, SKIPPED
    }

    public record Outcome(Kind kind, Long eventId) {
    }

    // 항목 하나가 한 트랜잭션 (공연장 국가 비교가 지연 로딩을 탄다)
    @Transactional
    public Outcome link(Provider provider, SourceSnapshot snapshot) {
        String key = snapshot.occurrenceKey();
        Map<String, SourceSnapshot> observed = Map.of(key, snapshot);

        // 1. 이미 연결된 외부 ID: 키 비교 후 같은 키의 행사만 갱신
        List<EventSource> known = sourceRepository.findAllByProviderAndExternalId(provider, snapshot.externalId());
        if (!known.isEmpty()) {
            Reconciliation r = applySources.reconcileOccurrences(provider, snapshot.externalId(), observed);
            if (r.moveProposed()) {
                return new Outcome(Kind.MOVE_PROPOSED, known.get(0).getEvent().getId());
            }
            for (EventSource source : known) {
                if (source.getOccurrenceKey().equals(key)) {
                    Long eventId = source.getEvent().getId();
                    applySources.apply(eventId, Map.of(provider, snapshot));
                    return new Outcome(Kind.REFRESHED, eventId);
                }
            }
            return new Outcome(r.discoveryIds().isEmpty() ? Kind.SKIPPED : Kind.DISCOVERED, null);
        }

        // 2. 처음 보는 ID: 같은 날짜·국가이고 이 제공자 출처가 없는 행사가 하나뿐일 때만 연결
        List<Event> matches = eventRepository.findAllByEventDateAndDeletedAtIsNull(snapshot.eventDate()).stream()
                .filter(e -> sourceRepository.findByEventIdAndProvider(e.getId(), provider).isEmpty())
                .filter(e -> sameCountry(e, snapshot))
                .toList();
        if (matches.size() == 1) {
            Long eventId = matches.get(0).getId();
            applySources.apply(eventId, Map.of(provider, snapshot));
            log.info("[출처 연결] provider={}, externalId={}, eventId={}", provider, snapshot.externalId(), eventId);
            return new Outcome(Kind.LINKED, eventId);
        }

        // 3. 없거나 여럿: 발견 행 (이미 있으면 core 가 억제)
        Reconciliation r = applySources.reconcileOccurrences(provider, snapshot.externalId(), observed);
        return new Outcome(r.discoveryIds().isEmpty() ? Kind.SKIPPED : Kind.DISCOVERED, null);
    }

    // 양쪽 다 국가를 알 때만 비교한다
    private static boolean sameCountry(Event event, SourceSnapshot snapshot) {
        String internal = event.getVenue() == null ? null : event.getVenue().getCountryCode();
        String external = snapshot.venue() == null ? null : snapshot.venue().countryCode();
        return internal == null || external == null || Objects.equals(internal.toUpperCase(), external.toUpperCase());
    }
}
