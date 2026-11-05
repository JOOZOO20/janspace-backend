package com.stagelog.media.api;

import static com.stagelog.common.constant.ApiVersion.V1_BASE;

import com.stagelog.common.dto.ResponseDTO;
import com.stagelog.media.application.MediaBackfill;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 미디어 백필 API. /admin/** 은 SecurityConfig 가 ADMIN 권한을 요구한다.
 * TODO: JAN-06 감사 로그(admin_audit_logs)가 병합되면 시작·중단을 감사 대상으로 연결한다
 */
@Slf4j
@Tag(name = "관리자 미디어 (Admin Media)", description = "기존 원본 이미지의 rendition 백필 API")
@RestController
@RequestMapping(V1_BASE + "/admin/media/backfill")
@RequiredArgsConstructor
public class AdminMediaController {

    private final MediaBackfill backfill;

    @Operation(summary = "백필 시작",
            description = "ownerType 의 기존 원본을 소유자 ID 순으로 읽어 asset·rendition 행과 변환 메시지를 만들고 초당 20건으로 jan-media-backfill 에 발행합니다. "
                    + "백그라운드로 돌고 바로 202 를 돌려줍니다. 이미 현재 원본으로 등록된 소유자는 건너뛰므로 중단 후 다시 부르면 이어서 진행합니다. "
                    + "변환이 꺼져 있으면 409 MEDIA_TRANSCODE_DISABLED, 실행 중이면 409 MEDIA_BACKFILL_RUNNING 입니다.")
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ResponseDTO<BackfillResponse> start(@RequestBody @Valid BackfillRequest request) {
        log.info("[미디어 백필 요청] ownerType={}, limit={}", request.ownerType(), request.limit());
        return ResponseDTO.okWithData(BackfillResponse.from(backfill.start(request.ownerType(), request.limit())));
    }

    @Operation(summary = "백필 상태", description = "마지막(또는 실행 중인) 백필의 진행 위치와 건수입니다.")
    @GetMapping
    public ResponseDTO<BackfillResponse> status() {
        return ResponseDTO.okWithData(BackfillResponse.from(backfill.status()));
    }

    @Operation(summary = "백필 중단", description = "다음 원본 전에 멈춥니다. 이미 발행한 메시지는 그대로 처리됩니다.")
    @DeleteMapping
    public ResponseDTO<BackfillResponse> stop() {
        log.info("[미디어 백필 중단 요청]");
        return ResponseDTO.okWithData(BackfillResponse.from(backfill.stop()));
    }
}
