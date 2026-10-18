package com.janspace.setlist.domain;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SetlistPageRepository extends JpaRepository<SetlistPage, Long> {

    List<SetlistPage> findAllByEventIdIn(Collection<Long> eventIds);

    List<SetlistPage> findAllByMergedAtIsNotNull();
}
