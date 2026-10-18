package com.janspace.ticket.application;

import com.janspace.event.application.EventView;
import com.janspace.ticket.domain.Ticket;

/**
 * 티켓 + 행사(공연명·날짜·시각·공연장은 행사 값) + 연번(등록 순 1부터) + 같은 행사의 내 기록 ID(없으면 null).
 */
public record TicketView(Ticket ticket, int serial, EventView event, Long recordId) {
}
