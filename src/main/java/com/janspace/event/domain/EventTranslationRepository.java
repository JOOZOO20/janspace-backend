package com.janspace.event.domain;

import com.janspace.common.locale.SupportedLocale;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventTranslationRepository extends JpaRepository<EventTranslation, Long> {

    List<EventTranslation> findAllByEventIdIn(Collection<Long> eventIds);

    Optional<EventTranslation> findByEventIdAndFieldAndLocale(Long eventId, TranslationField field, SupportedLocale locale);

    void deleteAllByEventIdAndField(Long eventId, TranslationField field);
}
