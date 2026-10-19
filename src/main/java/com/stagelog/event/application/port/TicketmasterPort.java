package com.stagelog.event.application.port;

import com.stagelog.event.domain.EventSourcePolicy.SourceSnapshot;
import java.util.List;

/**
 * Ticketmaster 아티스트 이벤트 조회. 이벤트마다 스냅샷 하나(externalId=이벤트 id, occurrenceKey=localDate).
 */
public interface TicketmasterPort {

    boolean isEnabled();

    List<SourceSnapshot> artistEvents();
}
