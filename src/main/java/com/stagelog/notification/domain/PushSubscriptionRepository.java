package com.stagelog.notification.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {

    // 소프트 삭제 행 포함 (되살림용). unique(endpoint) 라 최대 1건
    Optional<PushSubscription> findByEndpoint(String endpoint);

    List<PushSubscription> findAllByUserIdAndDeletedAtIsNull(Long userId);

    List<PushSubscription> findAllByUserIdInAndDeletedAtIsNull(Collection<Long> userIds);
}
