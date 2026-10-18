package com.janspace.photocard.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FrameRepository extends JpaRepository<Frame, Long> {

    Optional<Frame> findByIdAndDeletedAtIsNull(Long id);

    List<Frame> findAllByDeletedAtIsNullOrderByIdAsc();
}
