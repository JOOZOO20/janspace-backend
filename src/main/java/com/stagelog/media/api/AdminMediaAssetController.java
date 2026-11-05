package com.stagelog.media.api;

import static com.stagelog.common.constant.ApiVersion.V1_BASE;

import com.stagelog.common.dto.ResponseDTO;
import com.stagelog.media.application.MediaAssetQuery;
import com.stagelog.media.application.MediaOwnerType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 미디어 asset 진단 조회. ADMIN 과 MCP_READER 서비스 계정이 부른다 (SecurityConfig). 읽기 전용이다.
 */
@Slf4j
@Tag(name = "관리자 미디어 진단 (Admin Media Diagnosis)", description = "소유자별 asset·rendition 상태와 변환 메시지 실패 기록")
@RestController
@RequestMapping(V1_BASE + "/admin/media/assets")
@RequiredArgsConstructor
public class AdminMediaAssetController {

    private final MediaAssetQuery query;

    @Operation(summary = "미디어 asset 상태",
            description = "소유자의 asset 을 최신순으로, rendition 6개(상태·시도·Lease·마지막 오류·출력)와 각 rendition 변환 메시지의 Queue 실패 기록과 함께 돌려줍니다. "
                    + "current 는 소유자의 현재 원본과 같은 asset 인지입니다. asset 이 없으면 빈 배열.")
    @GetMapping
    public ResponseDTO<List<MediaAssetStatusResponse>> assets(
            @Parameter(description = "소유자 종류 (EVENT_POSTER, ALBUM, PHOTOCARD, FOURCUT)", example = "EVENT_POSTER")
            @RequestParam MediaOwnerType ownerType,
            @Parameter(description = "소유자 ID (행사 ID 등)", example = "12")
            @RequestParam long ownerId) {
        List<MediaAssetStatusResponse> assets = query.of(ownerType, ownerId).stream().map(MediaAssetStatusResponse::from).toList();
        log.info("[미디어 asset 상태 조회] ownerType={}, ownerId={}, assets={}", ownerType, ownerId, assets.size());
        return ResponseDTO.okWithData(assets);
    }
}
