package com.janspace.setlist.domain;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SetlistPageRepository extends JpaRepository<SetlistPage, Long> {

    List<SetlistPage> findAllByEventIdIn(Collection<Long> eventIds);

    List<SetlistPage> findAllByMergedAtIsNotNull();

    // SELECT ... FOR UPDATE
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from SetlistPage p where p.eventId = :eventId")
    Optional<SetlistPage> findByIdForUpdate(@Param("eventId") Long eventId);
}
