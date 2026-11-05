package com.stagelog.event.application;

import com.stagelog.ops.application.AdminAuditRecorder;
import com.stagelog.ops.application.AuditAction;
import com.stagelog.ops.application.AuditActor;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 필드 잠금 감사 로그. 잠금 상태가 실제로 바뀐 필드만 남긴다: 새로 잠근 필드는 FIELD_LOCK_SET, 풀린 필드는 FIELD_LOCK_UNSET 한 건에 모은다.
 * before·after 는 바뀐 필드별 잠금 상태(LOCKED / UNLOCKED)다. 호출자의 트랜잭션 안에서 부른다.
 */
final class LockAudit {

    static final String EVENT = "EVENT";
    static final String VENUE = "VENUE";

    private LockAudit() {
    }

    static <F extends Enum<F>> void record(AdminAuditRecorder audit, AuditActor actor, String targetType, Long targetId,
                                           Set<F> before, Set<F> after) {
        record(audit, actor, AuditAction.FIELD_LOCK_SET, targetType, targetId, after, before, "UNLOCKED", "LOCKED");
        record(audit, actor, AuditAction.FIELD_LOCK_UNSET, targetType, targetId, before, after, "LOCKED", "UNLOCKED");
    }

    // changedIn 에만 있는 필드 = 상태가 바뀐 필드. enum 순서로 담아 JSON 이 결정적이다
    private static <F extends Enum<F>> void record(AdminAuditRecorder audit, AuditActor actor, AuditAction action, String targetType,
                                                   Long targetId, Set<F> changedIn, Set<F> other, String from, String to) {
        Map<String, String> before = new LinkedHashMap<>();
        Map<String, String> after = new LinkedHashMap<>();
        changedIn.stream().filter(f -> !other.contains(f)).sorted().forEach(f -> {
            before.put(f.name(), from);
            after.put(f.name(), to);
        });
        if (!before.isEmpty()) {
            audit.record(actor, action, targetType, String.valueOf(targetId), before, after, null);
        }
    }
}
