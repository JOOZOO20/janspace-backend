package com.janspace.event.domain;

import com.janspace.common.locale.SupportedLocale;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VenueTranslationRepository extends JpaRepository<VenueTranslation, Long> {

    List<VenueTranslation> findAllByVenueIdIn(Collection<Long> venueIds);

    Optional<VenueTranslation> findByVenueIdAndFieldAndLocale(Long venueId, TranslationField field, SupportedLocale locale);
}
