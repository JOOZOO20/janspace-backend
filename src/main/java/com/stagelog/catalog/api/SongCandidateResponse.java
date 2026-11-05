package com.stagelog.catalog.api;

import com.stagelog.catalog.application.CatalogQuery.SongCandidate;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 관리자 곡 후보 정규화 검색 결과 항목.
 */
@Schema(description = "곡 후보 (정규화 기준)")
public record SongCandidateResponse(
        @Schema(description = "곡 ID", example = "3", requiredMode = Schema.RequiredMode.REQUIRED)
        Long id,
        @Schema(description = "원어 제목", example = "떠도는 별들을 위해", requiredMode = Schema.RequiredMode.REQUIRED)
        String title,
        @Schema(description = "검색어와 맞은 표기 (원어 제목·번역·별칭 중 하나)", example = "for stars who wander",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String matchedText,
        @Schema(description = "일치 정도", example = "EXACT", requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"EXACT", "PREFIX", "CONTAINS"})
        String matchType,
        @Schema(description = "새 선택에 쓸 수 있는 곡인지", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean selectable) {

    static SongCandidateResponse from(SongCandidate c) {
        return new SongCandidateResponse(c.id(), c.title(), c.matchedText(), c.matchType(), c.selectable());
    }
}
