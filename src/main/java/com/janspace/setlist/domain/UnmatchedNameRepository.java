package com.janspace.setlist.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UnmatchedNameRepository extends JpaRepository<UnmatchedName, Long> {

    List<UnmatchedName> findAllByOrderByIdDesc();

    boolean existsByEventIdAndRawName(Long eventId, String rawName);
}
