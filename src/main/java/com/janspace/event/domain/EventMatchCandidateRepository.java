package com.janspace.event.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventMatchCandidateRepository extends JpaRepository<EventMatchCandidate, Long> {

    List<EventMatchCandidate> findAllByEventId(Long eventId);

    List<EventMatchCandidate> findAllByEventIdAndProvider(Long eventId, Provider provider);

    List<EventMatchCandidate> findAllByProviderAndExternalIdAndOccurrenceKey(Provider provider, String externalId, String occurrenceKey);
}
