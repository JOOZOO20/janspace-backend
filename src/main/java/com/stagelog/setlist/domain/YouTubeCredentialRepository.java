package com.stagelog.setlist.domain;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface YouTubeCredentialRepository extends JpaRepository<YouTubeCredential, Byte> {

    // 처음 표시한 쪽만 1을 돌려받는다. 동시에 실패한 동기화가 여럿이어도 운영자 알림은 한 번
    @Modifying
    @Query("update YouTubeCredential c set c.invalidAt = :at where c.id = :id and c.invalidAt is null")
    int markInvalid(@Param("id") Byte id, @Param("at") Instant at);
}
