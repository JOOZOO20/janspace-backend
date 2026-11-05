package com.stagelog.ops.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stagelog.account.application.ServiceAccountAuthenticator;
import com.stagelog.account.domain.ServiceAccount;
import com.stagelog.account.domain.ServiceAccountRepository;
import com.stagelog.account.domain.ServiceAccountRole;
import com.stagelog.bootstrap.RequestIdFilter;
import com.stagelog.bootstrap.ServiceAccountAuthenticationFilter;
import com.stagelog.common.locale.SupportedLocale;
import com.stagelog.event.application.EventCommand;
import com.stagelog.event.application.EventCommand.EventInput;
import com.stagelog.event.application.SourceChangeReviewUseCase;
import com.stagelog.event.application.VenueCommand;
import com.stagelog.event.application.VenueCommand.VenueUpdate;
import com.stagelog.event.domain.EventLockableField;
import com.stagelog.event.domain.EventRepository;
import com.stagelog.event.domain.EventSourceChange;
import com.stagelog.event.domain.EventSourceChangeRepository;
import com.stagelog.event.domain.Provider;
import com.stagelog.event.domain.SourceChangeField;
import com.stagelog.event.domain.SourceChangeStatus;
import com.stagelog.event.domain.Venue;
import com.stagelog.event.domain.VenueRepository;
import com.stagelog.notification.application.AdminPushCommand;
import com.stagelog.ops.application.port.MessageQueuePort;
import com.stagelog.ops.application.port.MessageQueuePort.Received;
import com.stagelog.ops.domain.AgentActionRequest;
import com.stagelog.ops.domain.AgentActionRequestRepository;
import com.stagelog.ops.domain.AgentActionType;
import com.stagelog.ops.domain.AgentRequestStatus;
import com.stagelog.setlist.application.UnmatchedUseCase;
import com.stagelog.setlist.application.port.PlaylistPort.ItemSync;
import com.stagelog.setlist.domain.UnmatchedName;
import com.stagelog.setlist.domain.UnmatchedNameRepository;
import com.stagelog.setlist.domain.YouTubeCredential;
import com.stagelog.setlist.domain.YouTubeCredentialRepository;
import com.stagelog.setlist.support.SetlistIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 감사 로그 수용 기준: 대상 조치 전부가 API 로 실행될 때 1건씩 남고(AuditAction 전수), 조치가 롤백되면 0건이다.
 * 잠금 상태가 바뀌지 않은 수정은 남기지 않는다.
 */
class AdminAuditIntegrationTest extends SetlistIntegrationTest {

    static final String REQUEST_ID = "audit-test-1";
    static final String MCP_KEY = "audit-test-mcp-requester-key-0123456789";
    static final JsonMapper MAPPER = JsonMapper.builder().build();
    static final List<String> FIXTURE_TABLES = List.of("admin_audit_logs", "event_source_changes", "venues", "push_deliveries",
            "push_jobs", "push_subscriptions", "agent_action_requests");

    @Autowired
    EventRepository events;
    @Autowired
    EventSourceChangeRepository changes;
    @Autowired
    VenueRepository venues;
    @Autowired
    UnmatchedNameRepository unmatchedNames;
    @Autowired
    YouTubeCredentialRepository credentials;
    @Autowired
    EventCommand eventCommand;
    @Autowired
    VenueCommand venueCommand;
    @Autowired
    SourceChangeReviewUseCase reviewUseCase;
    @Autowired
    UnmatchedUseCase unmatchedUseCase;
    @Autowired
    AdminPushCommand adminPushCommand;
    @Autowired
    AdminAuditRecorder recorder;
    @Autowired
    TransactionTemplate transactionTemplate;
    @Autowired
    WebApplicationContext webContext;
    @Autowired
    ServiceAccountRepository serviceAccounts;
    @Autowired
    AgentActionRequestRepository agentRequests;
    @Autowired
    AgentRequestUseCase agentRequestUseCase;
    // DLQ redrive 의 SQS 호출만 목으로 둔다 (감사 로그 기록이 이 테스트의 대상)
    @MockitoBean
    MessageQueuePort messageQueue;

    Long eventId;

    @BeforeEach
    void setUp() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        FIXTURE_TABLES.forEach(t -> jdbc.execute("TRUNCATE TABLE " + t));
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        // request_id 를 확인하려고 운영과 같이 RequestIdFilter 를 체인에 넣는다
        mvc = MockMvcBuilders.webAppContextSetup(webContext).addFilters(new RequestIdFilter()).apply(springSecurity()).build();
        eventId = newEvent("스타더스트 클럽 단독 공연", today.plusDays(1));
    }

    @Test
    @DisplayName("대상 조치 전부가 API 로 실행되면 감사 로그를 1건씩 남기고, 잠금 상태가 그대로인 수정은 남기지 않는다")
    void every_action_writes_one_log() throws Exception {
        List<String> actions = new ArrayList<>();

        // 필드 잠금: 생성, PATCH 잠금, PATCH 지우기(해제), 이미 잠긴 필드 재수정(로그 없음), unlock, 포스터, 공연장
        String created = mvc.perform(admin(post("/api/v1/admin/events")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":{\"original\":\"새 공연\"},\"kind\":\"CONCERT\",\"date\":\"" + today.plusDays(2) + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long createdId = json(created).get("data").get("id").asLong();
        expectOne(actions, "FIELD_LOCK_SET", "EVENT", createdId, Map.of("NAME", "UNLOCKED", "TYPE", "UNLOCKED", "EVENT_DATE", "UNLOCKED"),
                Map.of("NAME", "LOCKED", "TYPE", "LOCKED", "EVENT_DATE", "LOCKED"));

        patchEvent("{\"ticketUrl\":\"https://tickets.example/1\"}");
        expectOne(actions, "FIELD_LOCK_SET", "EVENT", eventId, Map.of("TICKET_URL", "UNLOCKED"), Map.of("TICKET_URL", "LOCKED"));
        patchEvent("{\"ticketUrl\":null}");
        expectOne(actions, "FIELD_LOCK_UNSET", "EVENT", eventId, Map.of("TICKET_URL", "LOCKED"), Map.of("TICKET_URL", "UNLOCKED"));
        patchEvent("{\"season\":\"2026 투어\"}");
        expectOne(actions, "FIELD_LOCK_SET", "EVENT", eventId, Map.of("SEASON", "UNLOCKED"), Map.of("SEASON", "LOCKED"));
        patchEvent("{\"season\":\"2026 전국투어\"}");
        assertThat(auditCount()).as("이미 잠긴 필드의 값만 바꾼 수정은 로그 없음").isEqualTo(actions.size());

        mvc.perform(admin(post("/api/v1/admin/events/{id}/unlock", eventId)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"field\":\"SEASON\"}")).andExpect(status().isOk());
        expectOne(actions, "FIELD_LOCK_UNSET", "EVENT", eventId, Map.of("SEASON", "LOCKED"), Map.of("SEASON", "UNLOCKED"));

        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenReturn(PutObjectResponse.builder().build());
        MockMultipartFile poster = new MockMultipartFile("file", "poster.png", "image/png", new byte[] {1, 2, 3});
        mvc.perform(admin(multipart("/api/v1/admin/events/{id}/poster", eventId).file(poster).with(r -> {
            r.setMethod("PUT");
            return r;
        }))).andExpect(status().isOk());
        expectOne(actions, "FIELD_LOCK_SET", "EVENT", eventId, Map.of("POSTER", "UNLOCKED"), Map.of("POSTER", "LOCKED"));

        Long venueId = venues.save(Venue.create("KSPO DOME", SupportedLocale.KO, "서울", "KR")).getId();
        mvc.perform(admin(patch("/api/v1/admin/venues/{id}", venueId)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"올림픽 체조경기장\"}")).andExpect(status().isOk());
        expectOne(actions, "FIELD_LOCK_SET", "VENUE", venueId, Map.of("NAME", "UNLOCKED"), Map.of("NAME", "LOCKED"));

        // PENDING 제안 승인·거절
        Long approveId = pendingChange(SourceChangeField.STARTS_AT, today.plusDays(1) + "T19:00").getId();
        Long rejectId = pendingChange(SourceChangeField.EVENT_DATE, today.plusDays(3).toString()).getId();
        mvc.perform(admin(post("/api/v1/admin/source-changes/{id}/approve", approveId))).andExpect(status().isOk());
        expectOne(actions, "PROPOSAL_APPROVE", "SOURCE_CHANGE", approveId, Map.of("status", "PENDING"), Map.of("status", "APPLIED"));
        mvc.perform(admin(post("/api/v1/admin/source-changes/{id}/reject", rejectId))).andExpect(status().isOk());
        expectOne(actions, "PROPOSAL_REJECT", "SOURCE_CHANGE", rejectId, Map.of("status", "PENDING"), Map.of("status", "REJECTED"));

        // 미매칭 곡명 수동 연결
        Long songId = newSong("떠도는 별들을 위해", null, "vidA");
        Long unmatchedId = unmatched("Wandering Stars");
        mvc.perform(admin(post("/api/v1/admin/songs/unmatched/{id}", unmatchedId)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"songId\":" + songId + "}")).andExpect(status().isOk());
        expectOne(actions, "UNMATCHED_SONG_LINK", "UNMATCHED_NAME", unmatchedId, null,
                Map.of("songId", songId, "alias", "Wandering Stars"));

        // 푸시 수동 재시도
        long deliveryId = failedDelivery();
        mvc.perform(admin(post("/api/v1/admin/push-deliveries/{id}/retry", deliveryId))).andExpect(status().isOk());
        expectOne(actions, "PUSH_DELIVERY_RETRY", "PUSH_DELIVERY", deliveryId, Map.of("status", "FAILED"), Map.of("status", "RETRY"));

        // 유튜브 수동 동기화 (성공한 뒤 별도 트랜잭션으로 기록)
        credentials.save(new YouTubeCredential("rt"));
        when(playlistPort.create(anyString(), anyString(), any())).thenReturn("PL1");
        when(playlistPort.syncItems(anyString(), any(), any())).thenReturn(ItemSync.ok(List.of(), 0));
        mvc.perform(admin(post("/api/v1/admin/events/{id}/setlist/playlist", eventId))).andExpect(status().isOk());
        expectOne(actions, "PLAYLIST_SYNC", "EVENT", eventId, null, Map.of("playlistId", "PL1"));

        // MCP 조치 요청 승인·거절. 요청은 MCP 서비스 계정이 만들고(로그 없음), 승인 뒤 실행된 재생목록 동기화는 PLAYLIST_SYNC 로 따로 남는다
        serviceAccounts.deleteAll();
        serviceAccounts.save(new ServiceAccount("jan-mcp-audit", ServiceAccountAuthenticator.sha256Hex(MCP_KEY),
                Set.of(ServiceAccountRole.MCP_REQUESTER)));
        long approveRequest = agentRequest("RETRY_PLAYLIST_SYNC", String.valueOf(eventId), null);
        assertThat(auditCount()).as("요청 생성은 로그 없음").isEqualTo(actions.size());
        mvc.perform(admin(post("/api/v1/admin/agent-requests/{id}/approve", approveRequest))).andExpect(status().isOk());
        actions.add("AGENT_REQUEST_APPROVE");
        Map<String, Object> approveRow = jdbc.queryForMap("SELECT * FROM admin_audit_logs WHERE action = 'AGENT_REQUEST_APPROVE'");
        assertThat(approveRow.get("target_type")).isEqualTo("AGENT_REQUEST");
        assertThat(approveRow.get("target_id")).isEqualTo(String.valueOf(approveRequest));
        assertThat(parse(approveRow.get("before_json"))).isEqualTo(json("{\"status\":\"PENDING\"}"));
        assertThat(parse(approveRow.get("after_json"))).isEqualTo(json(MAPPER.writeValueAsString(
                Map.of("status", "APPROVED", "type", "RETRY_PLAYLIST_SYNC", "targetRef", String.valueOf(eventId)))));
        expectOne(actions, "PLAYLIST_SYNC", "EVENT", eventId, null, Map.of("playlistId", "PL1"));
        long rejectRequest = agentRequest("RETRY_PLAYLIST_SYNC", String.valueOf(eventId), null);
        mvc.perform(admin(post("/api/v1/admin/agent-requests/{id}/reject", rejectRequest))).andExpect(status().isOk());
        expectOne(actions, "AGENT_REQUEST_REJECT", "AGENT_REQUEST", rejectRequest, Map.of("status", "PENDING"),
                Map.of("status", "REJECTED", "type", "RETRY_PLAYLIST_SYNC", "targetRef", String.valueOf(eventId)));

        // DLQ redrive: 관리자 API 와 MCP 조치 승인 경로. SQS 호출이 섞여 재투입 뒤 별도 트랜잭션으로 남는다
        String dlqMessage = "6f1c0d1e-8a4b-4f0e-9b7a-2c3d4e5f6a7b";
        when(messageQueue.receive(eq("jan-poster-ingest-dlq"), anyInt(), anyInt(), any())).thenReturn(List.of(
                new Received("sqs-1", "receipt-1", "{\"messageId\":\"" + dlqMessage + "\",\"eventId\":1}", 5)));
        mvc.perform(admin(post("/api/v1/admin/queues/jan-poster-ingest/dead-letters/redrive")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"messageIds\":[\"" + dlqMessage + "\"]}")).andExpect(status().isOk());
        actions.add("DLQ_REDRIVE");
        expectLast(actions, "DLQ_REDRIVE", "DEAD_LETTER_QUEUE", "jan-poster-ingest", Map.of("redriven", List.of(dlqMessage)));
        long redriveRequest = agentRequest("REDRIVE_DEAD_LETTER", dlqMessage, "{\"queue\":\"jan-poster-ingest\"}");
        mvc.perform(admin(post("/api/v1/admin/agent-requests/{id}/approve", redriveRequest))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("EXECUTED"));
        actions.add("AGENT_REQUEST_APPROVE");
        actions.add("DLQ_REDRIVE");
        expectLast(actions, "DLQ_REDRIVE", "DEAD_LETTER_QUEUE", "jan-poster-ingest", Map.of("redriven", List.of(dlqMessage)));

        // 전수: 모든 AuditAction 이 위에서 한 번 이상 실행됐다
        assertThat(actions.stream().map(AuditAction::valueOf).collect(Collectors.toSet()))
                .isEqualTo(EnumSet.allOf(AuditAction.class));
        assertThat(jdbc.queryForList("SELECT DISTINCT CONCAT(actor_type, ':', actor_id, ':', request_id) FROM admin_audit_logs",
                String.class)).containsExactly("ADMIN:" + ADMIN + ":" + REQUEST_ID);
    }

    @Test
    @DisplayName("조치 트랜잭션이 롤백되면 감사 로그도 0건이고, 트랜잭션 밖 record 는 거부된다. 재생목록 동기화가 실패하면 로그 없음")
    void rolled_back_actions_leave_no_log() throws Exception {
        AuditActor actor = AuditActor.admin(ADMIN);
        Long changeId = pendingChange(SourceChangeField.STARTS_AT, today.plusDays(1) + "T19:00").getId();
        Long venueId = venues.save(Venue.create("KSPO DOME", SupportedLocale.KO, "서울", "KR")).getId();
        Long songId = newSong("떠도는 별들을 위해", null, "vidA");
        Long unmatchedId = unmatched("Wandering Stars");
        long deliveryId = failedDelivery();
        Long agentRequestId = agentRequests.save(new AgentActionRequest(AgentActionType.RETRY_PLAYLIST_SYNC, String.valueOf(eventId),
                null, "진단 결과", "[\"run:1\"]", "jan-mcp")).getId();
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenReturn(PutObjectResponse.builder().build());
        EventInput lockSeason = new EventInput(null, null, null, null, null, null, "2026 투어", null, null, null, null);

        List<Runnable> actions = List.of(
                () -> reviewUseCase.approve(changeId, actor),
                () -> reviewUseCase.reject(changeId, actor),
                () -> eventCommand.update(eventId, lockSeason, actor),
                () -> eventCommand.uploadPoster(eventId, new byte[] {1}, "image/png", actor),
                () -> venueCommand.adminUpdate(venueId, new VenueUpdate("올림픽 체조경기장", null, null, null), actor),
                () -> unmatchedUseCase.link(unmatchedId, songId, actor),
                () -> adminPushCommand.retryDelivery(deliveryId, actor),
                () -> agentRequestUseCase.reject(agentRequestId, ADMIN));
        for (Runnable action : actions) {
            transactionTemplate.executeWithoutResult(status -> {
                action.run();
                assertThat(auditCount()).as("조치 트랜잭션 안에서는 로그가 보인다").isEqualTo(1);
                status.setRollbackOnly();
            });
            assertThat(auditCount()).isZero();
        }
        // 조치도 함께 롤백됐다
        assertThat(changes.findById(changeId).orElseThrow().getStatus()).isEqualTo(SourceChangeStatus.PENDING);
        assertThat(events.findById(eventId).orElseThrow().getLockedFields()).isEmpty();
        assertThat(unmatchedNames.findById(unmatchedId)).isPresent();
        assertThat(agentRequests.findById(agentRequestId).orElseThrow().getStatus()).isEqualTo(AgentRequestStatus.PENDING);

        // unlock 은 잠긴 필드가 있어야 로그가 남는다
        eventCommand.update(eventId, lockSeason, actor);
        jdbc.execute("TRUNCATE TABLE admin_audit_logs");
        transactionTemplate.executeWithoutResult(status -> {
            eventCommand.unlock(eventId, EventLockableField.SEASON, actor);
            status.setRollbackOnly();
        });
        assertThat(auditCount()).isZero();
        assertThat(events.findById(eventId).orElseThrow().isLocked(EventLockableField.SEASON)).isTrue();

        assertThatThrownBy(() -> recorder.record(actor, AuditAction.FIELD_LOCK_SET, "EVENT", "1", null, null, null))
                .isInstanceOf(IllegalTransactionStateException.class);

        // 채널 미연결로 동기화가 실패하면 기록하지 않는다
        mvc.perform(admin(post("/api/v1/admin/events/{id}/setlist/playlist", eventId))).andExpect(status().isConflict());
        assertThat(auditCount()).isZero();
    }

    @Test
    @DisplayName("GET /admin/audit-logs: 대상·주체·기간으로 거르고 최신순 페이지, before/after 는 JSON 객체, 개인정보 키는 저장 전 제외")
    void query_filters_and_pages() throws Exception {
        AuditActor actor = AuditActor.admin(ADMIN);
        transactionTemplate.executeWithoutResult(status -> {
            recorder.record(actor, AuditAction.FIELD_LOCK_SET, "EVENT", "1", Map.of("NAME", "UNLOCKED"),
                    Map.of("NAME", "LOCKED", "email", "fan@example.com", "userId", 7), null);
            recorder.record(actor, AuditAction.FIELD_LOCK_UNSET, "EVENT", "1", null, null, "사유");
            recorder.record(AuditActor.system("test"), AuditAction.PUSH_DELIVERY_RETRY, "PUSH_DELIVERY", "5", null, null, null);
        });

        mvc.perform(admin(get("/api/v1/admin/audit-logs")).param("targetType", "EVENT").param("targetId", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].action").value("FIELD_LOCK_UNSET"))
                .andExpect(jsonPath("$.data.content[0].reason").value("사유"))
                .andExpect(jsonPath("$.data.content[0].before").doesNotExist());
        mvc.perform(admin(get("/api/v1/admin/audit-logs")).param("targetType", "EVENT").param("page", "1").param("size", "1"))
                .andExpect(jsonPath("$.data.content[0].action").value("FIELD_LOCK_SET"))
                .andExpect(jsonPath("$.data.content[0].actorType").value("ADMIN"))
                .andExpect(jsonPath("$.data.content[0].actorId").value(String.valueOf(ADMIN)))
                .andExpect(jsonPath("$.data.content[0].before.NAME").value("UNLOCKED"))
                .andExpect(jsonPath("$.data.content[0].after.NAME").value("LOCKED"))
                .andExpect(jsonPath("$.data.content[0].after.email").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].after.userId").doesNotExist());
        mvc.perform(admin(get("/api/v1/admin/audit-logs")).param("actorId", "test"))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].targetType").value("PUSH_DELIVERY"));
        mvc.perform(admin(get("/api/v1/admin/audit-logs")).param("from", Instant.now().plusSeconds(60).toString()))
                .andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(admin(get("/api/v1/admin/audit-logs")).param("to", Instant.now().plusSeconds(60).toString()).param("size", "999"))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.size").value(100));
        mvc.perform(get("/api/v1/admin/audit-logs").with(user(USER))).andExpect(status().isForbidden());
    }

    private static <B extends AbstractMockHttpServletRequestBuilder<B>> B admin(B request) {
        return request.with(admin()).header("X-Request-Id", REQUEST_ID);
    }

    private ResultActions patchEvent(String body) throws Exception {
        return mvc.perform(admin(patch("/api/v1/admin/events/{id}", eventId)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    // 직전 조치로 감사 로그가 정확히 1건 늘었고 그 행이 기대한 조치·대상·바뀐 필드다
    private void expectOne(List<String> actions, String action, String targetType, long targetId,
                           Map<String, ?> before, Map<String, ?> after) throws Exception {
        actions.add(action);
        assertThat(auditCount()).as(action).isEqualTo(actions.size());
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM admin_audit_logs ORDER BY id DESC LIMIT 1");
        assertThat(row.get("action")).isEqualTo(action);
        assertThat(row.get("target_type")).isEqualTo(targetType);
        assertThat(row.get("target_id")).isEqualTo(String.valueOf(targetId));
        assertThat(parse(row.get("before_json"))).isEqualTo(before == null ? null : json(MAPPER.writeValueAsString(before)));
        assertThat(parse(row.get("after_json"))).isEqualTo(after == null ? null : json(MAPPER.writeValueAsString(after)));
    }

    // MCP 서비스 계정으로 조치 요청 생성 (PENDING)
    private long agentRequest(String type, String targetRef, String payload) throws Exception {
        String body = "{\"type\":\"" + type + "\",\"targetRef\":\"" + targetRef
                + "\",\"reason\":\"진단 결과 재시도 필요\",\"evidenceRefs\":[\"run:1\"]" + (payload == null ? "" : ",\"payload\":" + payload) + "}";
        String response = mvc.perform(post("/api/v1/admin/agent-requests").header(ServiceAccountAuthenticationFilter.HEADER, MCP_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json(response).get("data").get("id").asLong();
    }

    // 감사 로그 수가 actions 와 같고 마지막 행이 기대한 조치·대상(문자열 ID)·after 다
    private void expectLast(List<String> actions, String action, String targetType, String targetId, Map<String, ?> after) {
        assertThat(auditCount()).as(action).isEqualTo(actions.size());
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM admin_audit_logs ORDER BY id DESC LIMIT 1");
        assertThat(row.get("action")).isEqualTo(action);
        assertThat(row.get("target_type")).isEqualTo(targetType);
        assertThat(row.get("target_id")).isEqualTo(targetId);
        assertThat(row.get("before_json")).isNull();
        assertThat(parse(row.get("after_json"))).isEqualTo(json(MAPPER.writeValueAsString(after)));
    }

    private EventSourceChange pendingChange(SourceChangeField field, String proposed) {
        return changes.save(new EventSourceChange(events.findById(eventId).orElseThrow(), field, null, proposed, Provider.KOPIS,
                Instant.now()));
    }

    // 미매칭 행은 셋리 페이지를 참조한다
    private Long unmatched(String rawName) {
        jdbc.update("INSERT IGNORE INTO setlist_pages (event_id, created_at, updated_at) VALUES (?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                eventId);
        return unmatchedNames.save(new UnmatchedName(eventId, rawName)).getId();
    }

    // 자동 시도를 다 쓴 FAILED 배달 1건. 다른 조치의 일정 변경이 eventId 에 잡을 예약할 수 있어 별도 행사에 둔다
    private long failedDelivery() {
        Long pushEventId = newEvent("푸시 공연", today.plusDays(5));
        jdbc.update("INSERT INTO push_subscriptions (user_id, endpoint, p256dh, auth, created_at, updated_at) "
                + "VALUES (?, 'https://push.example/1', 'p', 'a', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", USER);
        jdbc.update("INSERT INTO push_jobs (event_id, kind, due_at, status, created_at, updated_at) "
                + "VALUES (?, 'RECORD', UTC_TIMESTAMP(6), 'DONE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", pushEventId);
        jdbc.update("INSERT INTO push_deliveries (job_id, subscription_id, user_id, status, attempts, created_at, updated_at) "
                + "SELECT j.id, s.id, ?, 'FAILED', 3, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6) FROM push_jobs j, push_subscriptions s "
                + "WHERE j.event_id = ? AND s.endpoint = 'https://push.example/1'", USER, pushEventId);
        return jdbc.queryForObject("SELECT d.id FROM push_deliveries d JOIN push_jobs j ON j.id = d.job_id WHERE j.event_id = ?",
                Long.class, pushEventId);
    }

    private int auditCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs", Integer.class);
    }

    private static JsonNode json(String text) {
        return MAPPER.readTree(text);
    }

    private static JsonNode parse(Object column) {
        return column == null ? null : json(column.toString());
    }
}
