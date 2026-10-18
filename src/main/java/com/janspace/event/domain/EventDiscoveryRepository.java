package com.janspace.event.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventDiscoveryRepository extends JpaRepository<EventDiscovery, Long> {

    Optional<EventDiscovery> findByProviderAndExternalIdAndOccurrenceKey(Provider provider, String externalId, String occurrenceKey);

    List<EventDiscovery> findAllByOrderByCreatedAtDescIdDesc();

    List<EventDiscovery> findAllByEventId(Long eventId);
}
