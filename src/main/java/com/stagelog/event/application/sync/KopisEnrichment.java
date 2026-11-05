package com.stagelog.event.application.sync;

import com.stagelog.event.application.ApplySourcesUseCase;
import com.stagelog.event.application.CandidateUseCase;
import com.stagelog.event.application.EventQuery;
import com.stagelog.event.application.PosterKeys;
import com.stagelog.event.application.port.KopisPort;
import com.stagelog.event.application.port.KopisPort.Listing;
import com.stagelog.event.application.port.KopisPort.Poster;
import com.stagelog.event.application.port.PosterStoragePort;
import com.stagelog.event.application.sync.SyncRunner.SyncResult;
import com.stagelog.event.domain.Event;
import com.stagelog.event.domain.EventRepository;
import com.stagelog.event.domain.EventSource;
import com.stagelog.event.domain.EventSourcePolicy.SourceSnapshot;
import com.stagelog.event.domain.EventSourceRepository;
import com.stagelog.event.domain.EventStatus;
import com.stagelog.event.domain.Provider;
import com.stagelog.ops.application.OperatorAlert;
import com.stagelog.ops.application.Outbox;
import com.stagelog.ops.application.SyncRunQuery;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * KOPIS 보강. KOPIS 출처가 없는 활성 행사마다 행사 날짜로 공연목록을 검색해 후보가 하나면 연결·보강, 여럿이면
 * 매칭 후보로 남긴다. 이어서 마지막 성공 실행 이후(afterdate) 바뀐 공연 중 이미 연결된 것을 다시 읽어 갱신한다.
 * 항목(행사·증분 공연) 하나의 파싱·저장 오류는 sync_item_failures 에 남기고 다음 항목을 계속 처리하며, 실패가 있으면 실행을 PARTIAL 로
 * 끝낸다(OK 가 아니므로 afterdate 커서가 전진하지 않아 다음 실행이 같은 구간을 다시 가져온다). 다만 연속 3건 실패는 KOPIS 장애로 보고
 * 실행을 FAILED 로 멈춘다: 행사마다 KOPIS 호출이 2~4회라 장애 때 계속하면 타임아웃(최대 15초)이 행사 수만큼 곱해지고, 그동안 스케줄러
 * 스레드를 같이 쓰는 다른 잡(푸시 디스패처)이 밀린다. jan.sync.kopis.isolate-items.enabled=false 면 첫 오류에서 멈추는 이전 동작.
 * jan.pipeline.async-apply.enabled 면 출처 저장과 Outbox 기록만 하고, 접기와 포스터 다운로드는 Queue Consumer 가 한다
 * (포스터 다운로드 지연이 동기화 시간에 묶이지 않는다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KopisEnrichment {

    static final int DAYS_BACK = 3;
    static final int DAYS_AHEAD = 120;
    static final int MAX_WINDOW_DAYS = 31;
    static final int MAX_CONSECUTIVE_FAILURES = 3;
    private static final Pattern HANGUL = Pattern.compile("^[가-힣]{2,}$");
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", "jpg", "image/png", "png", "image/gif", "gif", "image/webp", "webp");

    // 생성자 주입 대상이 아니라 final 이 아니다 (@RequiredArgsConstructor 가 @Value 를 옮기지 않는다)
    @Value("${jan.storage.versioned-key.enabled:true}")
    private boolean versionedKey;
    @Value("${jan.sync.kopis.isolate-items.enabled:true}")
    private boolean isolateItems;
    @Value("${jan.pipeline.async-apply.enabled:false}")
    private boolean asyncApply;

    private final KopisPort port;
    private final EventRepository eventRepository;
    private final EventSourceRepository sourceRepository;
    private final ApplySourcesUseCase applySources;
    private final CandidateUseCase candidateUseCase;
    private final PosterStoragePort posterStorage;
    private final SyncRunQuery syncRunQuery;
    private final SyncRunner runner;
    private final OperatorAlert operatorAlert;
    private final Outbox outbox;
    private final TransactionTemplate transactions;

    public SyncResult enrich() {
        return enrich(LocalDate.now(EventQuery.LIST_ZONE));
    }

    public SyncResult enrich(LocalDate today) {
        if (!port.isEnabled()) {
            return SyncResult.skipped(Provider.KOPIS.name(), "KOPIS_SERVICE_KEY 또는 KOPIS_ARTIST_KEYWORD 미설정, 건너뜀");
        }
        // 증분 기준일은 이번 실행을 기록하기 전에 읽는다
        LocalDate afterDate = syncRunQuery.lastSucceededAt(Provider.KOPIS.name())
                .map(at -> at.atZone(EventQuery.LIST_ZONE).toLocalDate()).orElse(null);
        return runner.run(Provider.KOPIS.name(), () -> doEnrich(today.minusDays(DAYS_BACK), today.plusDays(DAYS_AHEAD), afterDate));
    }

    private String doEnrich(LocalDate from, LocalDate to, LocalDate afterDate) {
        // 1. 연결된 KOPIS 출처: eventId 집합과 mt20id -> 출처들
        Map<String, List<EventSource>> linked = new LinkedHashMap<>();
        Set<Long> linkedEventIds = new LinkedHashSet<>();
        for (EventSource s : sourceRepository.findAllByProvider(Provider.KOPIS)) {
            linked.computeIfAbsent(s.getExternalId(), k -> new ArrayList<>()).add(s);
            linkedEventIds.add(s.getEvent().getId());
        }

        // 2. 미연결 활성 행사 매칭
        Counts counts = new Counts();
        for (Event event : eventRepository.findAllByStatusAndDeletedAtIsNullAndEventDateBetween(EventStatus.ACTIVE, from, to)) {
            // Lease 를 잃었으면 멈춘다 (이후 쓰기 금지). 항목 catch 밖에서 확인한다
            runner.ensureHeld();
            if (linkedEventIds.contains(event.getId())) {
                continue;
            }
            isolate(counts, "event-" + event.getId(), () -> match(event, counts));
        }

        // 3. 증분: 마지막 성공 이후 바뀐 공연 중 연결된 것 갱신 (목록 조회 실패는 실행 실패)
        if (afterDate != null && !linked.isEmpty()) {
            for (Listing listing : searchChanged(from, to, afterDate)) {
                for (EventSource source : linked.getOrDefault(listing.mt20id(), List.of())) {
                    runner.ensureHeld();
                    isolate(counts, listing.mt20id(), () -> refresh(source, listing, counts));
                }
            }
        }

        String summary = "KOPIS " + from + "~" + to + ": 연결 " + counts.matched + ", 후보 " + counts.candidates + ", 미매칭 "
                + counts.unmatched + ", 증분 갱신 " + counts.refreshed + (afterDate == null ? "" : " (afterdate " + afterDate + ")");
        if (counts.failed > 0) {
            throw new SyncRunner.PartialException(summary + ", 항목 실패 " + counts.failed);
        }
        log.info("[KOPIS 보강] summary={}", summary);
        if (counts.matched > 0 || counts.candidates > 0) {
            operatorAlert.send("[KOPIS 보강] " + summary);
        }
        return summary;
    }

    // 실행 하나의 항목 집계
    private static final class Counts {
        int matched;
        int candidates;
        int unmatched;
        int refreshed;
        int failed;
        int consecutiveFailures;
    }

    // 항목 하나 처리. 실패는 기록하고 다음 항목으로 (연속 실패가 상한을 넘으면 장애로 보고 실행을 멈춘다)
    private void isolate(Counts counts, String itemId, Runnable item) {
        if (!isolateItems) {
            item.run();
            return;
        }
        try {
            item.run();
            counts.consecutiveFailures = 0;
        } catch (RuntimeException e) {
            counts.failed++;
            counts.consecutiveFailures++;
            log.warn("[KOPIS 항목 실패] item={}", itemId, e);
            runner.recordItemFailure(itemId, e);
            if (counts.consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                throw new IllegalStateException("KOPIS 항목이 연속 " + counts.consecutiveFailures + "건 실패해 장애로 보고 중단합니다. 마지막 item="
                        + itemId, e);
            }
        }
    }

    private void match(Event event, Counts counts) {
        List<Listing> listings = searchFor(event);
        if (listings.isEmpty()) {
            counts.unmatched++;
            return;
        }
        if (listings.size() == 1) {
            List<SourceSnapshot> snapshots = port.snapshots(listings.get(0).mt20id(), event.getEventDate());
            if (snapshots.size() == 1) {
                linkAndEnrich(event, snapshots.get(0));
                counts.matched++;
                return;
            }
            // 같은 날 회차가 여럿(또는 상세 없음): 관리자가 고른다
            counts.candidates += register(event, snapshots);
            return;
        }
        for (Listing listing : listings) {
            counts.candidates += register(event, port.snapshots(listing.mt20id(), event.getEventDate()));
        }
    }

    private void refresh(EventSource source, Listing listing, Counts counts) {
        // 트랜잭션 밖이라 프록시 대신 다시 읽는다
        Event event = eventRepository.findByIdAndDeletedAtIsNull(source.getEvent().getId()).orElse(null);
        if (event == null) {
            return;
        }
        List<SourceSnapshot> snapshots = port.snapshots(listing.mt20id(), event.getEventDate());
        if (snapshots.size() == 1) {
            linkAndEnrich(event, snapshots.get(0));
            counts.refreshed++;
        } else {
            // 회차가 늘어난 경우 자동 갱신하지 않고 확인 대상으로
            counts.candidates += register(event, snapshots);
        }
    }

    // 행사 이름의 가장 긴 한글 토큰(2자 이상)과 아티스트 검색어(설정값)로 검색해 행사 날짜를 포함하는 공연만 (mt20id 중복 제거)
    private List<Listing> searchFor(Event event) {
        Set<String> terms = new LinkedHashSet<>();
        longestHangulToken(event.getName()).ifPresent(terms::add);
        terms.add(port.artistKeyword());
        Map<String, Listing> byId = new LinkedHashMap<>();
        for (String term : terms) {
            for (Listing listing : port.search(event.getEventDate(), event.getEventDate(), term, null)) {
                if (listing.covers(event.getEventDate())) {
                    byId.putIfAbsent(listing.mt20id(), listing);
                }
            }
        }
        return new ArrayList<>(byId.values());
    }

    // 한계: 증분 검색어는 아티스트 검색어 하나. 이름에 아티스트 이름이 없는 연결 공연(페스티벌)의 변경은 놓칠 수 있다 — 필요해지면 연결된 mt20id 마다 상세 재조회로 바꾼다
    private List<Listing> searchChanged(LocalDate from, LocalDate to, LocalDate afterDate) {
        Map<String, Listing> byId = new LinkedHashMap<>();
        for (LocalDate start = from; !start.isAfter(to); start = start.plusDays(MAX_WINDOW_DAYS)) {
            LocalDate end = start.plusDays(MAX_WINDOW_DAYS - 1).isAfter(to) ? to : start.plusDays(MAX_WINDOW_DAYS - 1);
            port.search(start, end, port.artistKeyword(), afterDate).forEach(l -> byId.putIfAbsent(l.mt20id(), l));
        }
        return new ArrayList<>(byId.values());
    }

    private int register(Event event, List<SourceSnapshot> snapshots) {
        int added = 0;
        for (SourceSnapshot snapshot : snapshots) {
            if (candidateUseCase.register(event.getId(), Provider.KOPIS, snapshot)) {
                added++;
            }
        }
        return added;
    }

    // 포스터를 S3 에 재저장한 키로 바꿔 접는다. 다운로드 실패면 이미 저장해 둔 이 행사의 key, 그것도 없으면 제공자 URL 그대로.
    // 버전 key: 매번 내려받아 내용 hash key 로 저장한다. 같은 포스터면 key 가 같아 PUT 이 없고(HEAD 200), 바뀐 포스터는 새 key 가 된다.
    // 기존 동작(플래그 off): 이미 S3 key 가 있으면 다시 받지 않는다 (KOPIS 가 포스터를 바꿔도 반영되지 않는다)
    private void linkAndEnrich(Event event, SourceSnapshot snapshot) {
        if (asyncApply) {
            collectAndQueuePoster(event, snapshot);
            return;
        }
        SourceSnapshot toApply = snapshot;
        String url = snapshot.posterUrl();
        if (url != null && url.startsWith("http")) {
            String current = event.getPosterKey();
            String stored = !versionedKey && current != null && current.startsWith(PosterKeys.fixedKey(event.getId(), ""))
                    ? current : storePoster(event.getId(), url).orElse(null);
            String fallback = versionedKey && PosterKeys.isOwnedBy(current, event.getId()) ? current : url;
            toApply = snapshot.withPosterUrl(stored != null ? stored : fallback);
        }
        applySources.collect(event.getId(), Map.of(Provider.KOPIS, toApply));
    }

    // 비동기: 포스터는 지금 받지 않고 이 행사가 가진 key(없으면 제공자 URL)로 접게 둔 뒤, 포스터 Consumer 가 받아 key 로 바꾼다.
    // 출처 저장·apply 메시지·poster 메시지는 한 트랜잭션이라 커밋되면 셋 다 있고 롤백되면 셋 다 없다.
    // ponytail: 제공자 포스터 URL 을 따로 저장하지 않아 바뀌었는지 모르므로 실행마다 연결 행사마다 poster 메시지를 낸다(수정 전처럼 매번 다운로드,
    //           같은 내용은 PUT 없음). 연결 공연이 많아져 비용이 보이면 원본 URL 을 저장해 바뀐 것만 낸다
    private void collectAndQueuePoster(Event event, SourceSnapshot snapshot) {
        String url = snapshot.posterUrl();
        boolean remote = url != null && url.startsWith("http");
        String current = event.getPosterKey();
        SourceSnapshot toCollect = remote && PosterKeys.isOwnedBy(current, event.getId()) ? snapshot.withPosterUrl(current) : snapshot;
        transactions.executeWithoutResult(status -> {
            applySources.collect(event.getId(), Map.of(Provider.KOPIS, toCollect));
            if (remote) {
                outbox.append(PosterIngestHandler.QUEUE, new PosterIngestHandler.Payload(event.getId(), url));
            }
        });
    }

    private Optional<String> storePoster(Long eventId, String url) {
        try {
            Optional<Poster> poster = port.downloadPoster(url);
            if (poster.isEmpty()) {
                return Optional.empty();
            }
            String contentType = poster.get().contentType() == null ? "image/jpeg" : poster.get().contentType().toLowerCase();
            String extension = EXTENSIONS.getOrDefault(contentType, extensionOf(url));
            return Optional.of(versionedKey
                    ? posterStorage.putVersioned(PosterKeys.versionedPrefix(eventId), extension, poster.get().bytes(), contentType)
                    : posterStorage.put(PosterKeys.fixedKey(eventId, extension), poster.get().bytes(), contentType));
        } catch (RuntimeException e) {
            log.warn("[KOPIS 포스터 재저장 실패] eventId={}, url={}", eventId, url, e);
            return Optional.empty();
        }
    }

    private static String extensionOf(String url) {
        int dot = url.lastIndexOf('.');
        String ext = dot < 0 || dot < url.length() - 5 ? "jpg" : url.substring(dot + 1).toLowerCase();
        return ext.matches("[a-z0-9]{2,4}") ? ext : "jpg";
    }

    static Optional<String> longestHangulToken(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String best = null;
        for (String token : name.split("[\\s,·/()\\[\\]]+")) {
            if (HANGUL.matcher(token).matches() && (best == null || token.length() > best.length())) {
                best = token;
            }
        }
        return Optional.ofNullable(best);
    }
}
