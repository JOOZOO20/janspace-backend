package com.janspace.account.api;

import static com.janspace.common.constant.ApiVersion.V1_BASE;

import com.janspace.account.api.dto.PlaceResponse;
import com.janspace.account.application.port.RegionSearchPort;
import com.janspace.common.auth.AuthenticatedUser;
import com.janspace.common.auth.CurrentUser;
import com.janspace.common.dto.ResponseDTO;
import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.BadRequestException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 출발지 검색. 한글 질의는 국내 시군구(Kakao), 그 외는 해외 도시(LocationIQ).
 * GET 이지만 로그인 필수다. 과금 외부 API 를 프록시하고, 결과는 로그인 필수인 PATCH /me 의 출발지로만 쓰인다.
 */
@Tag(name = "출발지 검색 (Places)", description = "출발지로 쓸 시·군·구·도시 검색")
@RestController
@RequestMapping(V1_BASE + "/places")
@RequiredArgsConstructor
public class PlaceController {

    private static final int MAX_QUERY_LENGTH = 100;

    private final RegionSearchPort regionSearchPort;

    @Operation(summary = "출발지 검색", description = "질의어로 시·군·구(국내) 또는 도시(해외)를 최대 12건 찾습니다. 로그인이 필요하며 토큰이 없으면 401 입니다.")
    @GetMapping
    public ResponseDTO<List<PlaceResponse>> search(
            @CurrentUser AuthenticatedUser user,
            @Parameter(description = "검색어 (1~100자)", example = "노원구") @RequestParam("q") String query) {
        String q = query.trim();
        if (q.isEmpty() || q.length() > MAX_QUERY_LENGTH) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT, "검색어는 1~100자여야 합니다.");
        }
        return ResponseDTO.okWithData(regionSearchPort.search(q).stream().map(PlaceResponse::from).toList());
    }
}
