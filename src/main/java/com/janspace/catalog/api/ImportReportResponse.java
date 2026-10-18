package com.janspace.catalog.api;

import com.janspace.catalog.application.ImportReport;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "곡 마스터 엑셀 import 결과")
public record ImportReportResponse(
        @Schema(description = "새로 만든 곡 수", example = "98", requiredMode = Schema.RequiredMode.REQUIRED)
        int created,
        @Schema(description = "갱신한 곡 수", example = "2", requiredMode = Schema.RequiredMode.REQUIRED)
        int updated,
        @Schema(description = "한국어 제목이 비어 건너뛴 행 수", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        int skipped,
        @Schema(description = "행 단위 오류 (행 번호와 사유)", example = "[\"7행: iTunes 곡 ID 가 숫자가 아닙니다: abc\"]",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<String> errors) {

    static ImportReportResponse from(ImportReport r) {
        return new ImportReportResponse(r.created(), r.updated(), r.skipped(), r.errors());
    }
}
