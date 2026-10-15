package com.janspace.catalog.api;

import static com.janspace.common.constant.ApiVersion.V1_BASE;

import com.janspace.catalog.application.CatalogQuery;
import com.janspace.common.dto.ResponseDTO;
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
 * 공개 카탈로그 조회. 보는 것은 자유이므로 인증이 없다.
 */
@Slf4j
@Tag(name = "카탈로그 (Catalog)", description = "곡·앨범 조회 API")
@RestController
@RequestMapping(V1_BASE)
@RequiredArgsConstructor
public class CatalogController {

    private static final int MAX_LIMIT = 200;

    private final CatalogQuery catalogQuery;

    @Operation(summary = "곡 검색·목록",
            description = "q 가 있으면 원어 제목·번역·별칭에서 대소문자 무시 부분 일치, q 가 없거나 비어 있으면 텍스트 조건 없이 전곡을 ID 순으로 돌려줍니다. "
                    + "selectable=true 는 새 선택(기록의 최고의 곡, 셋리 추가, 미매칭 연결)에 쓸 수 있는 곡만, false 는 선택에서 제외된 곡만, "
                    + "생략하면 둘 다 돌려줍니다. 기존 기록·셋리 표시에는 생략(전체)을, 곡 고르기 화면에는 selectable=true 를 씁니다.")
    @GetMapping("/songs")
    public ResponseDTO<List<SongResponse>> songs(
            @Parameter(description = "검색어 (선택). 비어 있으면 텍스트 조건 없음", example = "legend")
            @RequestParam(required = false) String q,
            @Parameter(description = "선택 가능 여부 필터 (선택). true = 선택 가능 곡만, false = 선택 제외 곡만, 생략 = 전체", example = "true")
            @RequestParam(required = false) Boolean selectable,
            @Parameter(description = "최대 개수 (선택, 1~200). 생략하면 조건에 맞는 곡 전부", example = "50")
            @RequestParam(required = false) Integer limit) {
        Integer bounded = limit == null ? null : Math.clamp(limit, 1, MAX_LIMIT);
        List<SongResponse> result = catalogQuery.search(q, selectable, bounded).stream().map(SongResponse::from).toList();
        log.info("[곡 검색] q={}, selectable={}, limit={}, 결과={}", q, selectable, bounded, result.size());
        return ResponseDTO.okWithData(result);
    }

    @Operation(summary = "앨범 목록", description = "전체 앨범을 발매일 순으로 돌려줍니다. 커버가 있으면 coverUrl 이 채워집니다.")
    @GetMapping("/albums")
    public ResponseDTO<List<AlbumResponse>> albums() {
        return ResponseDTO.okWithData(catalogQuery.findAlbums().stream().map(AlbumResponse::from).toList());
    }
}
