package com.stagelog.event.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.stagelog.event.application.EventDiagnosisQuery.Comparison;
import com.stagelog.event.application.EventDiagnosisQuery.PendingChange;
import com.stagelog.event.application.EventDiagnosisQuery.ProviderStatus;
import com.stagelog.event.application.EventDiagnosisQuery.SourceValue;
import com.stagelog.event.application.EventDiagnosisQuery.SyncStatus;
import com.stagelog.ops.application.SyncRunQuery.SyncRunSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * 진단용 행사 출처 응답 (동기화 상태, 출처 비교). MCP 진단 Tool 이 근거 ID(snapshotId·runId·changeId)로 인용한다.
 */
public final class EventDiagnosisResponses {

    private EventDiagnosisResponses() {
    }

    @Schema(description = "행사 동기화 상태")
    public record SyncStatusResponse(
            @Schema(description = "행사 ID", example = "12", requiredMode = Schema.RequiredMode.REQUIRED)
            Long eventId,
            @Schema(description = "행사 이름", example = "2026 콘서트", requiredMode = Schema.RequiredMode.REQUIRED)
            String eventName,
            @Schema(description = "연결된 출처별 최근 Snapshot 과 마지막 동기화 실행", requiredMode = Schema.RequiredMode.REQUIRED)
            List<ProviderStatusResponse> providers,
            @Schema(description = "확인 대기(PENDING) 출처 변경 수", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
            int pendingChangeCount,
            @Schema(description = "확인 대기(PENDING) 출처 변경", requiredMode = Schema.RequiredMode.REQUIRED)
            List<PendingChangeResponse> pendingChanges,
            @Schema(description = "관리자 잠금(Field lock) 필드", example = "[\"NAME\"]", requiredMode = Schema.RequiredMode.REQUIRED)
            List<String> lockedFields) {

        static SyncStatusResponse from(SyncStatus s) {
            return new SyncStatusResponse(s.eventId(), s.eventName(), s.providers().stream().map(ProviderStatusResponse::from).toList(),
                    s.pendingChanges().size(), s.pendingChanges().stream().map(PendingChangeResponse::from).toList(), s.lockedFields());
        }
    }

    @Schema(description = "출처별 최근 Snapshot 과 마지막 실행")
    public record ProviderStatusResponse(
            @Schema(description = "제공자", example = "KOPIS", requiredMode = Schema.RequiredMode.REQUIRED)
            String provider,
            @Schema(description = "Snapshot ID (event_sources.id)", example = "7", requiredMode = Schema.RequiredMode.REQUIRED)
            Long snapshotId,
            @Schema(description = "외부 ID", example = "PF900001", requiredMode = Schema.RequiredMode.REQUIRED)
            String externalId,
            @Schema(description = "Snapshot 수집 시각 (UTC)", example = "2026-09-24T03:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
            Instant fetchedAt,
            @Schema(description = "그 출처의 가장 최근 동기화 실행 (출처 단위). 실행 이력이 없으면 null", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            LastRunResponse lastRun) {

        static ProviderStatusResponse from(ProviderStatus p) {
            return new ProviderStatusResponse(p.provider(), p.snapshotId(), p.externalId(), p.fetchedAt(),
                    p.lastRun() == null ? null : LastRunResponse.from(p.lastRun()));
        }
    }

    @Schema(description = "동기화 실행 요약")
    public record LastRunResponse(
            @Schema(description = "실행 ID (sync_runs.id)", example = "42", requiredMode = Schema.RequiredMode.REQUIRED)
            Long runId,
            @Schema(description = "실행 상태", example = "FAILED", requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"RUNNING", "OK", "FAILED"})
            String status,
            @Schema(description = "시작 시각 (UTC)", example = "2026-09-24T03:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
            Instant startedAt,
            @Schema(description = "종료 시각 (UTC). 실행 중이면 null", example = "2026-09-24T03:00:12Z", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
            Instant finishedAt,
            @Schema(description = "결과 요약 또는 실패 사유", example = "행사 12건 갱신", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String message) {

        static LastRunResponse from(SyncRunSummary r) {
            return new LastRunResponse(r.id(), r.status(), r.startedAt(), r.finishedAt(), r.summary());
        }
    }

    @Schema(description = "확인 대기 출처 변경")
    public record PendingChangeResponse(
            @Schema(description = "변경 요청 ID (event_source_changes.id)", example = "3", requiredMode = Schema.RequiredMode.REQUIRED)
            Long changeId,
            @Schema(description = "필드", example = "STARTS_AT", requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"EVENT_DATE", "STARTS_AT", "VENUE"})
            String field,
            @Schema(description = "현재 값", example = "2026-10-23T19:00", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String currentValue,
            @Schema(description = "제안 값 (VENUE 는 공연장 ID)", example = "2026-10-23T18:00", requiredMode = Schema.RequiredMode.REQUIRED)
            String proposedValue,
            @Schema(description = "제안한 제공자", example = "KOPIS", requiredMode = Schema.RequiredMode.REQUIRED)
            String provider,
            @Schema(description = "감지 시각 (UTC)", example = "2026-10-01T03:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
            Instant detectedAt) {

        static PendingChangeResponse from(PendingChange c) {
            return new PendingChangeResponse(c.id(), c.field(), c.currentValue(), c.proposedValue(), c.provider(), c.detectedAt());
        }
    }

    @Schema(description = "한 필드의 출처 비교")
    public record ComparisonResponse(
            @Schema(description = "행사 ID", example = "12", requiredMode = Schema.RequiredMode.REQUIRED)
            Long eventId,
            @Schema(description = "필드", example = "NAME", requiredMode = Schema.RequiredMode.REQUIRED)
            String field,
            @Schema(description = "이 필드의 출처 우선순위 (앞이 우선)", example = "[\"NAVER\",\"KOPIS\",\"TICKETMASTER\"]",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            List<String> priority,
            @Schema(description = "관리자 잠금 여부", example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
            boolean locked,
            @Schema(description = "행사의 현재 값 (VENUE 는 공연장 ID)", example = "2026 콘서트", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String currentValue,
            @Schema(description = "우선순위로 고른 제공자. 출처 값이 없으면 null", example = "NAVER", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String pickedProvider,
            @Schema(description = "우선순위로 고른 값 (VENUE 는 공연장 이름)", example = "2026 콘서트", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String pickedValue,
            @Schema(description = "현재 값의 이유", example = "PRIORITY", requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"NO_SOURCE_VALUE", "LOCKED", "PRIORITY", "PENDING_REVIEW", "REJECTED", "WAITING_EVENT_DATE", "NOT_FOLDED"})
            String reason,
            @Schema(description = "PENDING_REVIEW·REJECTED 의 근거 변경 요청 ID", example = "3", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            Long sourceChangeId,
            @Schema(description = "출처별 값 (우선순위 순)", requiredMode = Schema.RequiredMode.REQUIRED)
            List<SourceValueResponse> values) {

        static ComparisonResponse from(Comparison c) {
            return new ComparisonResponse(c.eventId(), c.field(), c.priority(), c.locked(), c.currentValue(), c.pickedProvider(),
                    c.pickedValue(), c.reason(), c.sourceChangeId(), c.values().stream().map(SourceValueResponse::from).toList());
        }
    }

    @Schema(description = "출처 하나의 값")
    public record SourceValueResponse(
            @Schema(description = "제공자", example = "KOPIS", requiredMode = Schema.RequiredMode.REQUIRED)
            String provider,
            @Schema(description = "우선순위 순번 (1 이 최우선, 0 은 이 필드에 쓰이지 않는 출처)", example = "2", requiredMode = Schema.RequiredMode.REQUIRED)
            int rank,
            @Schema(description = "Snapshot ID (event_sources.id)", example = "7", requiredMode = Schema.RequiredMode.REQUIRED)
            Long snapshotId,
            @Schema(description = "출처가 준 값 (VENUE 는 공연장 이름). 주지 않았으면 null", example = "2026 콘서트", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String value,
            @Schema(description = "VENUE: 기존 공연장으로 해석된 ID. 다음 접기에서 새로 만들 공연장이면 null", example = "4",
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            Long venueId) {

        static SourceValueResponse from(SourceValue v) {
            return new SourceValueResponse(v.provider(), v.rank(), v.snapshotId(), v.value(), v.venueId());
        }
    }
}
