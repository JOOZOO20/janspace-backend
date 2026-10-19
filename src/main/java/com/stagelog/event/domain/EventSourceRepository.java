package com.stagelog.event.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventSourceRepository extends JpaRepository<EventSource, Long> {

    List<EventSource> findAllByEventIdIn(Collection<Long> eventIds);

    List<EventSource> findAllByEventId(Long eventId);

    Optional<EventSource> findByEventIdAndProvider(Long eventId, Provider provider);

    List<EventSource> findAllByProviderAndExternalId(Provider provider, String externalId);

    List<EventSource> findAllByProvider(Provider provider);
}
