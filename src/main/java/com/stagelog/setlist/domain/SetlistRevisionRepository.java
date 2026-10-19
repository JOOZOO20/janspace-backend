package com.stagelog.setlist.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SetlistRevisionRepository extends JpaRepository<SetlistRevision, Long> {

    List<SetlistRevision> findAllByEventIdOrderByVersionDesc(Long eventId);

    Optional<SetlistRevision> findByEventIdAndVersion(Long eventId, int version);

    // 편집 횟수: [from, to) 안의 리비전 수 (EditQuotaPolicy)
    long countByEditorUserIdAndSourceInAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            Long editorUserId, Collection<RevisionSource> sources, Instant from, Instant to);

    // 탈퇴 시 편집자 흔적 제거 (UserWithdrawn)
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update SetlistRevision r set r.editorUserId = null where r.editorUserId = :userId")
    int clearEditor(@Param("userId") Long userId);
}
