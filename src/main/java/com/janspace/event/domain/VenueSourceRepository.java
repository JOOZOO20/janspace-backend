package com.janspace.event.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VenueSourceRepository extends JpaRepository<VenueSource, Long> {

    Optional<VenueSource> findByProviderAndExternalId(Provider provider, String externalId);
}
