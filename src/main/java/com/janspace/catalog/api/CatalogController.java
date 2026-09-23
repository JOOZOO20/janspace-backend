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

    @Operation(summary = "곡 검색·목록", description = "q 가 있으면 원어 제목·번역·별칭에서 대소문자 무시 부분 일치, 없으면 전곡을 ID 순으로 돌려줍니다.")
    @GetMapping("/songs")
    public ResponseDTO<List<SongResponse>> songs(
            @Parameter(description = "검색어 (선택)", example = "legend")
            @RequestParam(required = false) String q,
            @Parameter(description = "최대 개수 (1~200)", example = "50")
            @RequestParam(defaultValue = "50") int limit) {
        int bounded = Math.clamp(limit, 1, MAX_LIMIT);
        List<SongResponse> result = catalogQuery.search(q, bounded).stream().map(SongResponse::from).toList();
        log.info("[곡 검색] q={}, limit={}, 결과={}", q, bounded, result.size());
        return ResponseDTO.okWithData(result);
    }

    @Operation(summary = "앨범 목록", description = "전체 앨범을 발매일 순으로 돌려줍니다. 커버가 있으면 coverUrl 이 채워집니다.")
    @GetMapping("/albums")
    public ResponseDTO<List<AlbumResponse>> albums() {
        return ResponseDTO.okWithData(catalogQuery.findAlbums().stream().map(AlbumResponse::from).toList());
    }
}
