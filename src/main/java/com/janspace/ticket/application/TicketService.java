package com.janspace.ticket.application;

import com.janspace.attendance.application.AttendanceQuery.MyStatus;
import com.janspace.attendance.application.MyAttendanceQuery;
import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.BadRequestException;
import com.janspace.common.exception.predefined.ConflictException;
import com.janspace.common.exception.predefined.NotFoundException;
import com.janspace.event.application.EventQuery;
import com.janspace.event.application.EventView;
import com.janspace.ticket.domain.Ticket;
import com.janspace.ticket.domain.TicketRepository;
import com.janspace.ticket.domain.TicketSkin;
import com.janspace.ticket.domain.TicketSkinRepository;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 티켓북: 내 티켓 조회·꽂기·수정·삭제, 탈퇴 정리. 사용자 x 행사 1장(409 TICKET_ALREADY_EXISTS), 남의 티켓은 없는 것과 같이 404.
 * 기록 연결은 컬럼 없이 조회 시점에 같은 사용자·같은 행사의 기록(attendance 공개 계약)으로 구한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class TicketService {

    private final TicketRepository tickets;
    private final TicketSkinRepository skins;
    private final EventQuery eventQuery;
    private final MyAttendanceQuery attendance;

    // 행사 날짜 내림차순, 같은 날이면 시작 시각·연번 내림차순 (티켓북은 최근 공연이 위)
    @Transactional(readOnly = true)
    public List<TicketView> mine(Long userId) {
        Comparator<TicketView> order = Comparator.comparing((TicketView v) -> v.event().eventDate())
                .thenComparing(v -> v.event().startsAtLocal(), Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparingInt(TicketView::serial);
        return views(userId).stream().sorted(order.reversed()).toList();
    }

    // skinId 가 없으면 오늘 배포 중인 시즌 스킨(없으면 null = 기본 스킨)을 입힌다
    public TicketView create(Long userId, Long eventId, String seat, String accent, Long skinId) {
        requireEvent(eventId);
        if (tickets.existsByUserIdAndEventId(userId, eventId)) {
            throw new ConflictException(ErrorCode.TICKET_ALREADY_EXISTS);
        }
        Long skin = skinId == null ? currentSeasonSkinId() : requireSkin(skinId);
        Ticket saved = flush(new Ticket(userId, eventId, seat, accent, skin));
        log.info("[티켓 꽂기] userId={}, ticketId={}, eventId={}, skinId={}", userId, saved.getId(), eventId, skin);
        return view(userId, saved.getId());
    }

    // 인자가 null 이면 그 필드 유지, Optional.empty() 면 비우기 (eventId 는 비울 수 없다)
    public TicketView update(Long userId, Long ticketId, Optional<Long> eventId, Optional<String> seat, Optional<String> accent,
                             Optional<Long> skinId) {
        Ticket ticket = own(userId, ticketId);
        if (eventId != null) {
            Long target = eventId.orElseThrow(() -> new BadRequestException(ErrorCode.INVALID_INPUT, "eventId 는 비울 수 없습니다."));
            if (!target.equals(ticket.getEventId())) {
                requireEvent(target);
                if (tickets.existsByUserIdAndEventId(userId, target)) {
                    throw new ConflictException(ErrorCode.TICKET_ALREADY_EXISTS);
                }
                ticket.moveTo(target);
            }
        }
        if (seat != null) {
            ticket.changeSeat(seat.orElse(null));
        }
        if (accent != null) {
            ticket.changeAccent(accent.orElse(null));
        }
        if (skinId != null) {
            ticket.changeSkin(skinId.map(this::requireSkin).orElse(null));
        }
        flush(ticket);
        log.info("[티켓 수정] userId={}, ticketId={}, eventId={}", userId, ticketId, ticket.getEventId());
        return view(userId, ticketId);
    }

    public void delete(Long userId, Long ticketId) {
        tickets.delete(own(userId, ticketId));
        log.info("[티켓 삭제] userId={}, ticketId={}", userId, ticketId);
    }

    // UserWithdrawn 수신: 그 사용자의 티켓을 모두 물리 삭제한다
    public void withdraw(Long userId) {
        List<Ticket> rows = tickets.findAllByUserIdOrderByIdAsc(userId);
        tickets.deleteAll(rows);
        log.info("[탈퇴 티켓 정리] userId={}, rows={}", userId, rows.size());
    }

    // 연번 = 등록 순(1부터). 행사가 지워져(소프트 삭제) 보이지 않는 티켓은 목록에서 빠진다
    private List<TicketView> views(Long userId) {
        List<Ticket> rows = tickets.findAllByUserIdOrderByIdAsc(userId);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, EventView> events = eventQuery.viewsByIds(rows.stream().map(Ticket::getEventId).distinct().toList()).stream()
                .collect(Collectors.toMap(EventView::id, Function.identity()));
        Map<Long, MyStatus> statuses = attendance.statuses(userId);
        List<TicketView> out = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Ticket ticket = rows.get(i);
            EventView event = events.get(ticket.getEventId());
            if (event != null) {
                MyStatus status = statuses.get(ticket.getEventId());
                out.add(new TicketView(ticket, i + 1, event, status == null ? null : status.recordId()));
            }
        }
        return out;
    }

    private TicketView view(Long userId, Long ticketId) {
        return views(userId).stream().filter(v -> v.ticket().getId().equals(ticketId)).findFirst()
                .orElseThrow(() -> new NotFoundException(ErrorCode.TICKET_NOT_FOUND));
    }

    private Ticket own(Long userId, Long ticketId) {
        return tickets.findById(ticketId).filter(t -> t.isOwnedBy(userId))
                .orElseThrow(() -> new NotFoundException(ErrorCode.TICKET_NOT_FOUND));
    }

    // 동시에 같은 행사로 두 번 꽂으면 앞의 검사를 둘 다 지나므로 유일 제약 위반을 409 로 바꾼다
    private Ticket flush(Ticket ticket) {
        try {
            return tickets.saveAndFlush(ticket);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException(ErrorCode.TICKET_ALREADY_EXISTS);
        }
    }

    private void requireEvent(Long eventId) {
        if (eventQuery.find(eventId).isEmpty()) {
            throw new NotFoundException(ErrorCode.EVENT_NOT_FOUND);
        }
    }

    private Long requireSkin(Long skinId) {
        if (!skins.existsById(skinId)) {
            throw new NotFoundException(ErrorCode.TICKET_SKIN_NOT_FOUND);
        }
        return skinId;
    }

    private Long currentSeasonSkinId() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return skins.findAllByOrderByIdAsc().stream().filter(s -> s.isSeasonActiveOn(today)).findFirst()
                .map(TicketSkin::getId).orElse(null);
    }
}
