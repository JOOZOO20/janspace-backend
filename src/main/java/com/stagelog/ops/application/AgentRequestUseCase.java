package com.stagelog.ops.application;

import com.stagelog.common.exception.ErrorCode;
import com.stagelog.common.exception.predefined.BadRequestException;
import com.stagelog.common.exception.predefined.ConflictException;
import com.stagelog.common.exception.predefined.NotFoundException;
import com.stagelog.ops.application.port.AgentActionHandler;
import com.stagelog.ops.domain.AgentActionRequest;
import com.stagelog.ops.domain.AgentActionRequestRepository;
import com.stagelog.ops.domain.AgentActionType;
import com.stagelog.ops.domain.AgentRequestStatus;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * MCP 조치 요청: 생성(MCP_REQUESTER), 목록·승인·거절(ADMIN).
 * 생성은 조치를 실행하지 않는다. 같은 대상의 PENDING 은 DB UNIQUE(pending_key) 로 1건만 남고, 충돌하면 기존 요청 ID 를 돌려준다.
 * 승인은 행 잠금 트랜잭션에서 APPROVED 로 바꿔 커밋한 뒤, 트랜잭션 밖에서 기존 실행 경로(AgentActionHandler)를 부른다.
 * 실행 경로에 외부 호출(YouTube·setlist.fm)이 있어 DB 트랜잭션을 그동안 열어 두지 않기 위해서다.
 */
@Slf4j
@Service
public class AgentRequestUseCase {

    static final int LIST_LIMIT = 100;
    static final int MAX_EVIDENCE = 20;
    static final int MAX_REASON = 1000;
    // 대상은 지금 모든 유형에서 내부 ID 다. 문자열 대상이 필요한 유형이 생기면 그 유형만 규칙을 넓힌다
    static final Pattern NUMERIC_ID = Pattern.compile("[1-9][0-9]{0,18}");
    static final Pattern EVIDENCE_REF = Pattern.compile("[A-Za-z0-9_:#.-]{1,100}");

    private final AgentActionRequestRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final Map<AgentActionType, AgentActionHandler> handlers;

    public AgentRequestUseCase(AgentActionRequestRepository repository, TransactionTemplate transactionTemplate,
                               ObjectMapper objectMapper, List<AgentActionHandler> handlers) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        // 이름이 틀린 구현은 부팅 때 실패한다
        this.handlers = handlers.stream().collect(Collectors.toMap(h -> AgentActionType.valueOf(h.type()), Function.identity(),
                (a, b) -> {
                    throw new IllegalStateException("같은 조치 유형의 실행 구현이 둘입니다: " + a.type());
                }, () -> new EnumMap<>(AgentActionType.class)));
    }

    public record NewRequest(String type, String targetRef, Map<String, Object> payload, String reason,
                             List<String> evidenceRefs, String requestedBy) {
    }

    public record Created(Long id, boolean duplicate) {
    }

    public record AgentRequestView(Long id, String type, String targetRef, Map<String, Object> payload, String reason,
                                   List<String> evidenceRefs, String status, String requestedBy, String decidedBy,
                                   Instant decidedAt, Instant createdAt) {
    }

    public Created create(NewRequest request) {
        AgentActionType type = parseType(request.type());
        validate(type, request);
        AgentActionRequest entity = new AgentActionRequest(type, request.targetRef(),
                request.payload() == null || request.payload().isEmpty() ? null : objectMapper.writeValueAsString(request.payload()),
                request.reason().trim(), objectMapper.writeValueAsString(request.evidenceRefs()), request.requestedBy());
        try {
            Long id = transactionTemplate.execute(status -> repository.saveAndFlush(entity).getId());
            log.info("[MCP 조치 요청 생성] requestId={}, type={}, targetRef={}, requestedBy={}", id, type, request.targetRef(),
                    request.requestedBy());
            return new Created(id, false);
        } catch (DataIntegrityViolationException e) {
            // 같은 대상의 PENDING 이 이미 있다 (uk_agent_action_requests_pending). 다른 제약 위반이면 기존 PENDING 이 없으므로 그대로 올린다
            Long existing = repository.findFirstByTypeAndTargetRefAndStatus(type, request.targetRef(), AgentRequestStatus.PENDING)
                    .map(AgentActionRequest::getId)
                    .orElseThrow(() -> e);
            log.info("[MCP 조치 요청 중복] 기존 PENDING 을 돌려줌 requestId={}, type={}, targetRef={}, requestedBy={}", existing, type,
                    request.targetRef(), request.requestedBy());
            return new Created(existing, true);
        }
    }

    @Transactional(readOnly = true)
    public List<AgentRequestView> list(String status) {
        AgentRequestStatus parsed;
        try {
            parsed = AgentRequestStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT, "알 수 없는 상태입니다: " + status);
        }
        return repository.findAllByStatusOrderByIdDesc(parsed, Limit.of(LIST_LIMIT)).stream().map(this::view).toList();
    }

    // 1) 잠금 트랜잭션에서 PENDING 확인·APPROVED 커밋 2) 트랜잭션 밖 실행 3) EXECUTED/FAILED 기록. 실행 실패 예외는 관리자에게 그대로 간다
    public AgentRequestView approve(Long id, Long adminUserId) {
        AgentActionRequest approved = transactionTemplate.execute(status -> {
            AgentActionRequest request = lockPending(id);
            if (!handlers.containsKey(request.getType())) {
                log.warn("[MCP 조치 승인 불가 - 실행 경로 없음] requestId={}, type={}", id, request.getType());
                throw new ConflictException(ErrorCode.AGENT_ACTION_NOT_EXECUTABLE,
                        "아직 실행 경로가 연결되지 않은 조치 유형입니다: " + request.getType());
            }
            request.approve(String.valueOf(adminUserId), Instant.now());
            return request;
        });
        log.info("[MCP 조치 요청 승인] requestId={}, type={}, targetRef={}, adminId={}", id, approved.getType(),
                approved.getTargetRef(), adminUserId);
        try {
            handlers.get(approved.getType()).execute(approved.getTargetRef(), payload(approved));
        } catch (RuntimeException e) {
            finishExecution(id, false);
            log.warn("[MCP 조치 실행 실패] requestId={}, type={}, targetRef={}, error={}", id, approved.getType(),
                    approved.getTargetRef(), e.getClass().getSimpleName());
            throw e;
        }
        return finishExecution(id, true);
    }

    public AgentRequestView reject(Long id, Long adminUserId) {
        AgentRequestView rejected = transactionTemplate.execute(status -> {
            AgentActionRequest request = lockPending(id);
            request.reject(String.valueOf(adminUserId), Instant.now());
            return view(request);
        });
        log.info("[MCP 조치 요청 거절] requestId={}, type={}, targetRef={}, adminId={}", id, rejected.type(), rejected.targetRef(),
                adminUserId);
        return rejected;
    }

    private AgentRequestView finishExecution(Long id, boolean succeeded) {
        return transactionTemplate.execute(status -> {
            AgentActionRequest request = repository.findById(id)
                    .orElseThrow(() -> new NotFoundException(ErrorCode.AGENT_REQUEST_NOT_FOUND));
            request.finishExecution(succeeded);
            log.info("[MCP 조치 실행 종료] requestId={}, status={}", id, request.getStatus());
            return view(request);
        });
    }

    private AgentActionRequest lockPending(Long id) {
        AgentActionRequest request = repository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException(ErrorCode.AGENT_REQUEST_NOT_FOUND));
        if (!request.isPending()) {
            throw new ConflictException(ErrorCode.AGENT_REQUEST_NOT_PENDING);
        }
        return request;
    }

    private static AgentActionType parseType(String type) {
        try {
            return AgentActionType.valueOf(type == null ? "" : type.trim());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT,
                    "조치 유형은 " + Arrays.toString(AgentActionType.values()) + " 중 하나여야 합니다");
        }
    }

    private static void validate(AgentActionType type, NewRequest request) {
        if (request.targetRef() == null || !NUMERIC_ID.matcher(request.targetRef()).matches()) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT, "targetRef 는 대상의 숫자 ID 여야 합니다");
        }
        if (request.reason() == null || request.reason().isBlank() || request.reason().trim().length() > MAX_REASON) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT, "reason 은 1~" + MAX_REASON + "자여야 합니다");
        }
        List<String> evidence = request.evidenceRefs();
        if (evidence == null || evidence.isEmpty() || evidence.size() > MAX_EVIDENCE
                || evidence.stream().anyMatch(ref -> ref == null || !EVIDENCE_REF.matcher(ref).matches())) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT,
                    "evidenceRefs 는 근거 ID(예: run:42, snapshot:7) 1~" + MAX_EVIDENCE + "개여야 합니다");
        }
        if (type == AgentActionType.LINK_UNMATCHED_SONG && songId(request.payload()) == null) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT, "LINK_UNMATCHED_SONG 은 payload.songId(숫자)가 필요합니다");
        }
    }

    // payload.songId 를 숫자로 읽는다. 없거나 숫자가 아니면 null
    public static Long songId(Map<String, Object> payload) {
        Object value = payload == null ? null : payload.get("songId");
        if (value instanceof Number n && n.longValue() > 0) {
            return n.longValue();
        }
        return value instanceof String s && NUMERIC_ID.matcher(s).matches() ? Long.valueOf(s) : null;
    }

    private Map<String, Object> payload(AgentActionRequest request) {
        return request.getPayloadJson() == null ? Map.of()
                : objectMapper.readValue(request.getPayloadJson(), new TypeReference<Map<String, Object>>() {
                });
    }

    private AgentRequestView view(AgentActionRequest r) {
        return new AgentRequestView(r.getId(), r.getType().name(), r.getTargetRef(), payload(r), r.getReason(),
                objectMapper.readValue(r.getEvidenceJson(), new TypeReference<List<String>>() {
                }), r.getStatus().name(), r.getRequestedBy(), r.getDecidedBy(), r.getDecidedAt(), r.getCreatedAt());
    }
}
