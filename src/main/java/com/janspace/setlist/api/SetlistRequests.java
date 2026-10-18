package com.janspace.setlist.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Comparator;
import java.util.List;

/**
 * 셋리 API 요청 DTO 모음.
 */
public final class SetlistRequests {

    private SetlistRequests() {
    }

    @Schema(description = "편집 항목")
    public record EntryInput(
            @Schema(description = "곡 ID", example = "7", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull Long songId,
            @Schema(description = "순서. 값 자체는 정렬에만 쓰고 서버가 1..n 으로 다시 매긴다", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull Integer order) {
    }

    @Schema(description = "셋리 편집 요청 (전체 교체)")
    public record EditRequest(
            @Schema(description = "항목. 빈 목록이면 셋리를 비운다", example = "[{\"songId\":7,\"order\":1},{\"songId\":3,\"order\":2}]",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull List<@Valid EntryInput> entries,
            @Schema(description = "편집 기준 버전 (낙관적 잠금). 보내면 현재 버전과 다를 때 409 SETLIST_VERSION_CONFLICT", example = "3",
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            Integer version) {

        // order 오름차순의 곡 ID
        public List<Long> songIdsInOrder() {
            return entries.stream().sorted(Comparator.comparing(EntryInput::order)).map(EntryInput::songId).toList();
        }
    }

    @Schema(description = "되돌리기 요청")
    public record RevertRequest(
            @Schema(description = "되돌릴 리비전 버전", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull Integer version) {
    }

    @Schema(description = "미매칭 곡명 연결 요청")
    public record LinkRequest(
            @Schema(description = "연결할 곡 ID. 곡명이 그 곡의 별칭으로 등록된다", example = "7", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull Long songId) {
    }

    @Schema(description = "순서 확정 토글 요청")
    public record OrderConfirmedRequest(
            @Schema(description = "확정 여부", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull Boolean confirmed) {
    }
}
