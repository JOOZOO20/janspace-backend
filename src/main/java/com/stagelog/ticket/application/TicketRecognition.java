package com.stagelog.ticket.application;

import com.stagelog.attendance.application.AttendanceQuery.MyStatus;
import com.stagelog.attendance.application.MyAttendanceQuery;
import com.stagelog.common.locale.LocalizedText;
import com.stagelog.event.application.EventQuery;
import com.stagelog.event.application.EventSnapshot;
import com.stagelog.event.application.EventView;
import com.stagelog.ticket.domain.EventMatcher;
import com.stagelog.ticket.domain.Ticket;
import com.stagelog.ticket.domain.TicketRepository;
import com.stagelog.ticket.domain.TicketText;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기기 OCR 글자 줄 -> 행사 후보. 사진은 받지 않고, 받은 글자도 저장·로그하지 않는다(예매자 이름·예매 번호가 섞여 있을 수 있다).
 * 흐름: 글자에서 날짜 -> 그 날짜의 행사 -> 이름·시각 점수. 날짜가 없거나 그 날 행사가 없으면 참석 표시·기록했지만 티켓이 없는 행사를 대신 낸다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TicketRecognition {

    static final int MAX_CANDIDATES = 20;

    private final EventQuery eventQuery;
    private final MyAttendanceQuery attendance;
    private final TicketRepository tickets;

    public enum MatchStatus {
        MATCHED, NO_DATE, NO_EVENT_ON_DATE
    }

    /** 후보 행사. recordId·ticketId 는 그 행사의 내 기록·내 티켓(없으면 null). 대체 후보의 score 는 0. */
    public record Candidate(EventView event, int score, Long recordId, Long ticketId) {
    }

    public record Result(MatchStatus status, List<LocalDate> detectedDates, String seat, List<Candidate> candidates) {
    }

    public Result recognize(Long userId, List<String> lines) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        // 1. 글자에서 날짜·좌석 (공연명·공연장·시각은 행사 값을 쓴다)
        List<LocalDate> dates = TicketText.dates(lines, today);
        String seat = TicketText.seat(lines);
        Map<Long, Long> ticketIds = tickets.findAllByUserIdOrderByIdAsc(userId).stream()
                .collect(Collectors.toMap(Ticket::getEventId, Ticket::getId));
        Map<Long, MyStatus> statuses = attendance.statuses(userId);

        // 2. 그 날짜들의 행사를 이름·시각으로 대조
        List<Long> sameDay = dates.stream()
                .flatMap(d -> eventQuery.findBetween(d, d.plusDays(1)).stream())
                .map(EventSnapshot::id).distinct().toList();
        if (!sameDay.isEmpty()) {
            Map<Long, EventView> views = eventQuery.viewsByIds(sameDay).stream()
                    .collect(Collectors.toMap(EventView::id, Function.identity()));
            List<Candidate> ranked = EventMatcher.rank(lines, views.values().stream().map(TicketRecognition::target).toList(), today)
                    .stream().limit(MAX_CANDIDATES)
                    .map(s -> candidate(views.get(s.target().eventId()), s.score(), statuses, ticketIds))
                    .toList();
            log.info("[티켓 인식] userId={}, lines={}, dates={}, candidates={}, top={}", userId, lines.size(), dates.size(), ranked.size(),
                    ranked.get(0).event().id());
            return new Result(MatchStatus.MATCHED, dates, seat, ranked);
        }

        // 3. 대체 후보: 참석 표시했거나 기록했지만 티켓이 없는 행사, 오늘에 가까운 순
        List<Long> mine = statuses.keySet().stream().filter(id -> !ticketIds.containsKey(id)).toList();
        List<Candidate> fallback = eventQuery.viewsByIds(mine).stream()
                .sorted(Comparator.comparingLong((EventView v) -> Math.abs(ChronoUnit.DAYS.between(today, v.eventDate())))
                        .thenComparing(EventView::id))
                .limit(MAX_CANDIDATES)
                .map(v -> candidate(v, 0, statuses, ticketIds))
                .toList();
        MatchStatus status = dates.isEmpty() ? MatchStatus.NO_DATE : MatchStatus.NO_EVENT_ON_DATE;
        log.info("[티켓 인식 - 대체 후보] userId={}, lines={}, status={}, candidates={}", userId, lines.size(), status, fallback.size());
        return new Result(status, dates, seat, fallback);
    }

    private static EventMatcher.Target target(EventView view) {
        List<String> labels = new ArrayList<>(names(view.name()));
        if (view.venue() != null) {
            labels.addAll(names(view.venue().name()));
        }
        return new EventMatcher.Target(view.id(), view.eventDate(),
                view.startsAtLocal() == null ? null : view.startsAtLocal().toLocalTime(), labels);
    }

    private static List<String> names(LocalizedText text) {
        List<String> out = new ArrayList<>();
        out.add(text.original());
        out.addAll(text.translations().values());
        return out;
    }

    private static Candidate candidate(EventView view, int score, Map<Long, MyStatus> statuses, Map<Long, Long> ticketIds) {
        MyStatus status = statuses.get(view.id());
        return new Candidate(view, score, status == null ? null : status.recordId(), ticketIds.get(view.id()));
    }
}
