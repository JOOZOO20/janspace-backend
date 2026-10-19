package com.stagelog.event.application.sync;

import com.stagelog.event.application.ApplySourcesUseCase;
import com.stagelog.event.application.ApplySourcesUseCase.Reconciliation;
import com.stagelog.event.application.EventCommand;
import com.stagelog.event.application.EventQuery;
import com.stagelog.event.application.port.NaverCalendarPort;
import com.stagelog.event.application.sync.SyncRunner.SyncResult;
import com.stagelog.event.domain.EventDiscoveryRepository;
import com.stagelog.event.domain.EventSource;
import com.stagelog.event.domain.EventSourcePolicy.SourceSnapshot;
import com.stagelog.event.domain.EventSourceRepository;
import com.stagelog.event.domain.Provider;
import com.stagelog.ops.application.OperatorAlert;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 네이버 캘린더 동기화. 새 scheduleId 는 날짜마다 ACTIVE 행사를 만들고, 아는 scheduleId 는 회차 키 비교
 * (이동 제안 / 취소 의심 / 발견) 후 남은 키의 스냅샷을 갱신한다. 응답에서 사라진 scheduleId 는 관측 키 없음으로 비교한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NaverCalendarSync {

    static final int DAYS_BACK = 7;
    static final int DAYS_AHEAD = 120;

    private final NaverCalendarPort port;
    private final EventSourceRepository sourceRepository;
    private final EventDiscoveryRepository discoveryRepository;
    private final EventCommand eventCommand;
    private final ApplySourcesUseCase applySources;
    private final SyncRunner runner;
    private final OperatorAlert operatorAlert;

    public SyncResult sync() {
        return sync(LocalDate.now(EventQuery.LIST_ZONE));
    }

    public SyncResult sync(LocalDate today) {
        // 키 없이 호출하면 빈 목록이 와서 범위 안의 네이버 행사를 전부 취소 의심으로 바꿀 수 있다
        if (!port.isEnabled()) {
            return SyncResult.skipped(Provider.NAVER.name(), "NAVER_CALENDAR_PUBLISHED_KEY 미설정, 건너뜀");
        }
        return runner.run(Provider.NAVER.name(), () -> doSync(today.minusDays(DAYS_BACK), today.plusDays(DAYS_AHEAD)));
    }

    private String doSync(LocalDate from, LocalDate to) {
        // 1. 관측: scheduleId -> (회차 키 -> 스냅샷)
        Map<String, Map<String, SourceSnapshot>> observed = new LinkedHashMap<>();
        for (SourceSnapshot s : port.occurrences(from, to)) {
            observed.computeIfAbsent(s.externalId(), k -> new LinkedHashMap<>()).put(s.occurrenceKey(), s);
        }

        // 2. 아는 것: 조회 범위 안에 회차가 있는 NAVER 출처만 (범위 밖 옛 행사는 사라져도 취소 의심으로 보지 않는다)
        Map<String, List<EventSource>> known = new LinkedHashMap<>();
        for (EventSource source : sourceRepository.findAllByProvider(Provider.NAVER)) {
            LocalDate date = LocalDate.parse(source.getOccurrenceKey().substring(0, 10));
            if (!date.isBefore(from) && !date.isAfter(to)) {
                known.computeIfAbsent(source.getExternalId(), k -> new ArrayList<>()).add(source);
            }
        }
        // 아는 행사가 있는데 일정이 0건이면 응답 이상(비공식 API 의 오류 봉투·차단)으로 본다. 그대로 비교하면 범위 안 행사가 전부
        // 취소 의심이 되고, 취소 의심은 자동으로 풀리지 않는다. 실패로 기록해 운영자가 확인하게 한다
        if (observed.isEmpty() && !known.isEmpty()) {
            throw new IllegalStateException("네이버 캘린더 응답에 일정이 0건입니다. 알려진 일정 " + known.size()
                    + "건을 취소 의심으로 바꾸지 않고 중단합니다");
        }

        int created = 0;
        int refreshed = 0;
        int moves = 0;
        int suspected = 0;
        int discovered = 0;
        int suppressed = 0;
        int failed = 0;

        // 3. 관측된 scheduleId: 처음이면 생성, 알면 키 비교 후 갱신.
        // scheduleId 하나의 처리 실패가 뒤 일정을 매번 막지 않게 건너뛰고, 실행은 끝에서 FAILED 로 기록한다
        for (Map.Entry<String, Map<String, SourceSnapshot>> entry : observed.entrySet()) {
            try {
                List<EventSource> sources = known.get(entry.getKey());
                if (sources == null) {
                    for (SourceSnapshot snapshot : entry.getValue().values()) {
                        // 발견 행이 있는 회차(삭제된 행사의 회차 = DISMISSED 포함)는 관리자 판단 대상이라 자동 생성하지 않는다
                        if (discoveryRepository.findByProviderAndExternalIdAndOccurrenceKey(Provider.NAVER, entry.getKey(),
                                snapshot.occurrenceKey()).isPresent()) {
                            suppressed++;
                            continue;
                        }
                        eventCommand.createFromSource(Provider.NAVER, snapshot);
                        created++;
                    }
                    continue;
                }
                Reconciliation r = applySources.reconcileOccurrences(Provider.NAVER, entry.getKey(), entry.getValue());
                moves += r.moveProposed() ? 1 : 0;
                suspected += r.suspectedEventIds().size();
                discovered += r.discoveryIds().size();
                for (EventSource source : sources) {
                    SourceSnapshot snapshot = entry.getValue().get(source.getOccurrenceKey());
                    if (snapshot != null) {
                        applySources.apply(source.getEvent().getId(), Map.of(Provider.NAVER, snapshot));
                        refreshed++;
                    }
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("[네이버 캘린더 항목 실패] scheduleId={}", entry.getKey(), e);
            }
        }

        // 4. 응답에서 통째로 사라진 scheduleId -> 취소 의심
        for (String externalId : known.keySet()) {
            if (!observed.containsKey(externalId)) {
                suspected += applySources.reconcileOccurrences(Provider.NAVER, externalId, Map.of()).suspectedEventIds().size();
            }
        }

        String summary = "네이버 캘린더 " + from + "~" + to + ": 일정 " + observed.size() + "건, 생성 " + created
                + ", 갱신 " + refreshed + ", 이동 제안 " + moves + ", 취소 의심 " + suspected + ", 발견 " + discovered
                + ", 억제 " + suppressed;
        if (failed > 0) {
            throw new IllegalStateException(summary + ", 항목 실패 " + failed);
        }
        log.info("[네이버 캘린더 동기화] summary={}", summary);
        if (created > 0 || suspected > 0 || moves > 0) {
            operatorAlert.send("[네이버 캘린더 동기화] " + summary);
        }
        return summary;
    }
}
