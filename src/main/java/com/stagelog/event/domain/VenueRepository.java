package com.stagelog.event.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VenueRepository extends JpaRepository<Venue, Long> {

    // 이름이 같은 공연장은 하나로 본다 (출처·관리자 입력의 중복 생성 방지)
    Optional<Venue> findFirstByNameIgnoreCase(String name);
}
