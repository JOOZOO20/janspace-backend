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

/**
 * KOPIS 보강. KOPIS 출처가 없는 활성 행사마다 행사 날짜로 공연목록을 검색해 후보가 하나면 연결·보강, 여럿이면
 * 매칭 후보로 남긴다. 이어서 마지막 성공 실행 이후(afterdate) 바뀐 공연 중 이미 연결된 것을 다시 읽어 갱신한다.
 * 다른 동기화와 달리 항목 단위로 실패를 건너뛰지 않는다(fail-fast): 행사마다 KOPIS 호출이 2~4회라 KOPIS 장애 때 건너뛰며 계속하면
 * 타임아웃(최대 15초)이 행사 수만큼 곱해지고, 그동안 스케줄러 스레드를 같이 쓰는 다른 잡(푸시 디스패처)이 밀린다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KopisEnrichment {

    static final int DAYS_BACK = 3;
    static final int DAYS_AHEAD = 120;
    static final int MAX_WINDOW_DAYS = 31;
    private static final Pattern HANGUL = Pattern.compile("^[가-힣]{2,}$");
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", "jpg", "image/png", "png", "image/gif", "gif", "image/webp", "webp");

    // 생성자 주입 대상이 아니라 final 이 아니다 (@RequiredArgsConstructor 가 @Value 를 옮기지 않는다)
    @Value("${jan.storage.versioned-key.enabled:true}")
    private boolean versionedKey;

    private final KopisPort port;
    private final EventRepository eventRepository;
    private final EventSourceRepository sourceRepository;
    private final ApplySourcesUseCase applySources;
    private final CandidateUseCase candidateUseCase;
    private final PosterStoragePort posterStorage;
    private final SyncRunQuery syncRunQuery;
    private final SyncRunner runner;
    private final OperatorAlert operatorAlert;

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
        int matched = 0;
        int candidates = 0;
        int unmatched = 0;
        for (Event event : eventRepository.findAllByStatusAndDeletedAtIsNullAndEventDateBetween(EventStatus.ACTIVE, from, to)) {
            if (linkedEventIds.contains(event.getId())) {
                continue;
            }
            List<Listing> listings = searchFor(event);
            if (listings.isEmpty()) {
                unmatched++;
                continue;
            }
            if (listings.size() == 1) {
                List<SourceSnapshot> snapshots = port.snapshots(listings.get(0).mt20id(), event.getEventDate());
                if (snapshots.size() == 1) {
                    linkAndEnrich(event, snapshots.get(0));
                    matched++;
                    continue;
                }
                // 같은 날 회차가 여럿(또는 상세 없음): 관리자가 고른다
                candidates += register(event, snapshots);
                continue;
            }
            for (Listing listing : listings) {
                candidates += register(event, port.snapshots(listing.mt20id(), event.getEventDate()));
            }
        }

        // 3. 증분: 마지막 성공 이후 바뀐 공연 중 연결된 것 갱신
        int refreshed = 0;
        if (afterDate != null && !linked.isEmpty()) {
            for (Listing listing : searchChanged(from, to, afterDate)) {
                for (EventSource source : linked.getOrDefault(listing.mt20id(), List.of())) {
                    // 트랜잭션 밖이라 프록시 대신 다시 읽는다
                    Event event = eventRepository.findByIdAndDeletedAtIsNull(source.getEvent().getId()).orElse(null);
                    if (event == null) {
                        continue;
                    }
                    List<SourceSnapshot> snapshots = port.snapshots(listing.mt20id(), event.getEventDate());
                    if (snapshots.size() == 1) {
                        linkAndEnrich(event, snapshots.get(0));
                        refreshed++;
                    } else {
                        // 회차가 늘어난 경우 자동 갱신하지 않고 확인 대상으로
                        candidates += register(event, snapshots);
                    }
                }
            }
        }

        String summary = "KOPIS " + from + "~" + to + ": 연결 " + matched + ", 후보 " + candidates + ", 미매칭 " + unmatched
                + ", 증분 갱신 " + refreshed + (afterDate == null ? "" : " (afterdate " + afterDate + ")");
        log.info("[KOPIS 보강] summary={}", summary);
        if (matched > 0 || candidates > 0) {
            operatorAlert.send("[KOPIS 보강] " + summary);
        }
        return summary;
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
        SourceSnapshot toApply = snapshot;
        String url = snapshot.posterUrl();
        if (url != null && url.startsWith("http")) {
            String current = event.getPosterKey();
            String stored = !versionedKey && current != null && current.startsWith(PosterKeys.fixedKey(event.getId(), ""))
                    ? current : storePoster(event.getId(), url).orElse(null);
            String fallback = versionedKey && PosterKeys.isOwnedBy(current, event.getId()) ? current : url;
            toApply = snapshot.withPosterUrl(stored != null ? stored : fallback);
        }
        applySources.apply(event.getId(), Map.of(Provider.KOPIS, toApply));
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
