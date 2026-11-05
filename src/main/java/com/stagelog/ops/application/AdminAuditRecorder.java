package com.stagelog.ops.application;

import com.stagelog.ops.domain.AdminAuditLog;
import com.stagelog.ops.domain.AdminAuditLogRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 관리자 조치 감사 로그 기록 공개 계약. 다른 기능은 이 클래스만 호출한다.
 * record 는 호출자의 조치 트랜잭션에 합류해서(MANDATORY) 조치가 롤백되면 로그도 롤백된다. 트랜잭션 밖에서 부르면 예외가 난다.
 * 외부 호출이 섞여 하나의 DB 트랜잭션으로 묶을 수 없는 조치(재생목록 동기화 등)만 조치가 끝난 뒤 recordInNewTransaction 을 쓴다.
 * before·after 에는 바뀐 필드만 넣고, 개인정보로 보이는 키(이메일·토큰·사용자 ID 등)는 저장 전에 뺀다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuditRecorder {

    // RequestIdFilter.MDC_KEY 와 같은 값. ops 가 bootstrap 을 참조하면 순환이라 문자열로 둔다
    static final String REQUEST_ID_MDC_KEY = "requestId";
    static final Pattern PERSONAL_KEY = Pattern.compile("(?i)auth|.*(email|token|password|secret|userid|user_id|endpoint|p256dh).*");

    private final AdminAuditLogRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditActor actor, AuditAction action, String targetType, String targetId,
                       Map<String, ?> before, Map<String, ?> after, String reason) {
        save(actor, action, targetType, targetId, before, after, reason);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordInNewTransaction(AuditActor actor, AuditAction action, String targetType, String targetId,
                                       Map<String, ?> before, Map<String, ?> after, String reason) {
        save(actor, action, targetType, targetId, before, after, reason);
    }

    private void save(AuditActor actor, AuditAction action, String targetType, String targetId,
                      Map<String, ?> before, Map<String, ?> after, String reason) {
        AdminAuditLog saved = repository.save(AdminAuditLog.of(actor.type().name(), actor.id(), action.name(), targetType, targetId,
                json(before), json(after), reason, MDC.get(REQUEST_ID_MDC_KEY), Instant.now()));
        log.info("[감사 로그] auditId={}, action={}, actorType={}, targetType={}, targetId={}", saved.getId(), action,
                actor.type(), targetType, targetId);
    }

    private String json(Map<String, ?> fields) {
        if (fields == null || fields.isEmpty()) {
            return null;
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        fields.forEach((key, value) -> {
            if (PERSONAL_KEY.matcher(key).matches()) {
                // 호출자 실수로 조치가 실패하면 안 되므로 예외 대신 키만 빼고 남긴다
                log.warn("[감사 로그 개인정보 키 제외] key={}", key);
            } else {
                safe.put(key, value);
            }
        });
        return safe.isEmpty() ? null : objectMapper.writeValueAsString(safe);
    }
}
