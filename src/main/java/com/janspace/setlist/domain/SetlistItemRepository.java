package com.janspace.setlist.domain;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SetlistItemRepository extends JpaRepository<SetlistItem, Long> {

    List<SetlistItem> findAllByEventIdOrderByPosition(Long eventId);

    List<SetlistItem> findAllByEventIdInOrderByEventIdAscPositionAsc(Collection<Long> eventIds);

    boolean existsByEventIdAndSongId(Long eventId, Long songId);

    // 즉시 실행되는 벌크 삭제. 파생 deleteBy 는 flush 때 INSERT 가 먼저 나가 unique(event_id, song_id) 에 걸린다.
    // clearAutomatically 는 쓰지 않는다: 호출자가 들고 있는 SetlistPage 가 detach 되어 version 증가가 사라진다
    @Modifying(flushAutomatically = true)
    @Query("delete from SetlistItem i where i.eventId = :eventId")
    void deleteAllByEventId(@Param("eventId") Long eventId);
}
