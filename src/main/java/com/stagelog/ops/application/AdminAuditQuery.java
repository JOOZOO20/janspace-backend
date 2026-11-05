package com.stagelog.ops.application;

import com.stagelog.ops.domain.AdminAuditLog;
import com.stagelog.ops.domain.AdminAuditLogRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 감사 로그 조회. 조건은 모두 선택이며 최신순(같은 시각이면 id 역순)으로 페이지를 낸다.
 */
@Service
@RequiredArgsConstructor
public class AdminAuditQuery {

    private final AdminAuditLogRepository repository;

    public record AuditSearch(String targetType, String targetId, String actorId, Instant from, Instant to) {
    }

    @Transactional(readOnly = true)
    public Page<AdminAuditLog> search(AuditSearch search, int page, int size) {
        Sort newestFirst = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        return repository.search(search.targetType(), search.targetId(), search.actorId(), search.from(), search.to(),
                PageRequest.of(page, size, newestFirst));
    }
}
