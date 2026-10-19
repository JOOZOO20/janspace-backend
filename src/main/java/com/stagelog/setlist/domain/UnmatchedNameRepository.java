package com.stagelog.setlist.domain;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UnmatchedNameRepository extends JpaRepository<UnmatchedName, Long> {

    List<UnmatchedName> findAllByOrderByIdDesc();

    boolean existsByEventIdAndRawName(Long eventId, String rawName);

    // 비교는 DB 콜레이션(대소문자·악센트 무시)을 따른다. 위 exists 와 같은 기준이라 지운 이름을 다시 넣는 일이 없다
    long deleteByEventIdAndRawNameNotIn(Long eventId, Collection<String> rawNames);
}
