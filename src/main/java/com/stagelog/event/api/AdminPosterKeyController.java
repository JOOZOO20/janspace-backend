package com.stagelog.event.api;

import static com.stagelog.common.constant.ApiVersion.V1_BASE;

import com.stagelog.common.dto.ResponseDTO;
import com.stagelog.event.application.PosterKeyBackfill;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 포스터 key 백필 (JAN-02 고정 key -> 버전 key 1회 이전). /admin/** 은 SecurityConfig 가 ADMIN 권한을 요구한다.
 */
@Slf4j
@Tag(name = "관리자 포스터 key (Admin Poster Key)", description = "포스터 고정 key 를 내용 hash 버전 key 로 옮기는 백필 API")
@RestController
@RequestMapping(V1_BASE + "/admin/events/posters")
@RequiredArgsConstructor
public class AdminPosterKeyController {

    private final PosterKeyBackfill backfill;

    @Schema(description = "포스터 key 백필 결과")
    public record PosterBackfillResponse(
            @Schema(description = "버전 key 로 옮기고 삭제 예약한 고정 key 수", example = "40", requiredMode = Schema.RequiredMode.REQUIRED)
            int migrated,
            @Schema(description = "원본 객체를 읽지 못해 건너뛴 key 수 (서버 로그에 key 기록)", example = "0", requiredMode = Schema.RequiredMode.REQUIRED)
            int failed) {
    }

    @Operation(summary = "포스터 key 백필",
            description = "고정 key(events/{id}/poster.{ext}) 포스터를 내용 hash 버전 key 로 복사하고 events.poster_key 와 출처 스냅샷의 포스터 key 를 바꿉니다. "
                    + "이전 key 는 7일 뒤 삭제됩니다. 다시 실행하면 남은 고정 key 만 처리합니다.")
    @PostMapping("/versioned-key-backfill")
    public ResponseDTO<PosterBackfillResponse> backfill() {
        log.info("[관리자 포스터 key 백필 요청]");
        PosterKeyBackfill.Result result = backfill.run();
        return ResponseDTO.okWithData(new PosterBackfillResponse(result.migrated(), result.failed()));
    }
}
