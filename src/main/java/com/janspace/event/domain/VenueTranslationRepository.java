package com.janspace.event.domain;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VenueTranslationRepository extends JpaRepository<VenueTranslation, Long> {

    List<VenueTranslation> findAllByVenueIdIn(Collection<Long> venueIds);
}
