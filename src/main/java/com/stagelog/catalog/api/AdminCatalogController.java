package com.stagelog.catalog.api;

import static com.stagelog.common.constant.ApiVersion.V1_BASE;

import com.stagelog.catalog.application.CatalogCommand;
import com.stagelog.catalog.application.CatalogQuery;
import com.stagelog.common.dto.ResponseDTO;
import com.stagelog.common.exception.ErrorCode;
import com.stagelog.common.exception.predefined.BadRequestException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 관리자 카탈로그 관리. 관리자 권한 규칙은 보안 설정이 /admin/** 에 건다.
 */
@Slf4j
@Tag(name = "관리자 카탈로그 (Admin Catalog)", description = "곡·앨범 저장(병합), 엑셀 import, 커버 업로드, iTunes 후보 조회")
@RestController
@RequestMapping(V1_BASE + "/admin")
@RequiredArgsConstructor
public class AdminCatalogController {

    static final int SEARCH_LIMIT = 10;

    private final CatalogCommand catalogCommand;
    private final CatalogQuery catalogQuery;

    @Operation(summary = "곡 저장 (병합)",
            description = "id 가 있으면 그 곡을, 없으면 한국어 제목이 같은 곡을 갱신하고 없으면 새로 만듭니다(새 곡은 title 필수). "
                    + "보낸 키만 반영하고 키가 없는 필드는 기존 값을 유지하므로, 폼이 보내지 않는 albumId·selectable 이 지워지지 않습니다. "
                    + "aliases·itunesId·youtubeId·albumId 는 null 을 보내면 지웁니다(albumId null = 앨범 연결 해제). "
                    + "title 과 aliases 는 보내면 전체 교체입니다. 다른 곡의 별칭과 겹치면 409 CONFLICT, 없는 곡·앨범 ID 는 404.")
    @PutMapping("/songs")
    public ResponseDTO<SongResponse> upsertSong(@RequestBody SongUpsertRequest request) {
        log.info("[관리자 곡 저장 요청] id={}, title={}", request.id(), request.title() == null ? null : request.title().original());
        return ResponseDTO.okWithData(SongResponse.from(catalogCommand.upsertSong(request.toCommand())));
    }

    @Operation(summary = "곡 마스터 엑셀 import",
            description = "xlsx 첫 시트를 읽어 한국어 제목 기준으로 곡 저장(병합)과 같은 규칙으로 반영합니다. "
                    + "열 순서: 한국어 제목 | 영어 제목 | 중국어(번체) 제목 | 일본어 제목 | setlist.fm 표기(|로 구분) | iTunes 곡 ID | 유튜브 영상 ID | 앨범명(한국어). "
                    + "빈 칸은 기존 값을 유지하고(import 로는 값을 지우지 않음), 별칭은 기존 별칭에 더합니다. 없는 앨범명은 앨범을 만듭니다. "
                    + "시트에 없는 선택 가능 여부와 커버는 바꾸지 않습니다. 지우기는 곡 저장 API 에서 null 로 합니다.")
    @PostMapping(value = "/songs/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseDTO<ImportReportResponse> importSongs(
            @Parameter(description = "xlsx 파일 (multipart 필드명 file)", example = "songs.xlsx")
            @RequestPart("file") MultipartFile file) {
        log.info("[관리자 곡 import 요청] filename={}, bytes={}", file.getOriginalFilename(), file.getSize());
        try (InputStream in = file.getInputStream()) {
            return ResponseDTO.okWithData(ImportReportResponse.from(catalogCommand.importSongs(in)));
        } catch (IOException e) {
            throw new BadRequestException(ErrorCode.CATALOG_IMPORT_INVALID);
        }
    }

    @Operation(summary = "곡 후보 정규화 검색",
            description = "셋리 병합과 같은 정규화(앞뒤 문장부호·대소문자·악센트·연속 공백 무시)로 원어 제목·번역·별칭을 비교해 "
                    + "가까운 순(EXACT > PREFIX > CONTAINS) 상위 10곡을 돌려줍니다. 미매칭 곡명을 연결할 곡을 고를 때 씁니다. 정규화 결과가 비면 빈 목록.")
    @GetMapping("/songs/search")
    public ResponseDTO<List<SongCandidateResponse>> searchSongs(
            @Parameter(description = "검색어 (setlist.fm 표기 등)", example = "For Stars Who Wander!")
            @RequestParam String q) {
        List<SongCandidateResponse> candidates = catalogQuery.searchNormalized(q, SEARCH_LIMIT).stream()
                .map(SongCandidateResponse::from).toList();
        log.info("[관리자 곡 후보 검색] queryLength={}, count={}", q.length(), candidates.size());
        return ResponseDTO.okWithData(candidates);
    }

    @Operation(summary = "iTunes 곡 후보 조회", description = "iTunes Search(country=kr) 로 곡 ID·앨범 ID·아트워크 초안 후보를 최대 10건 돌려줍니다.")
    @GetMapping("/catalog/itunes")
    public ResponseDTO<List<TrackCandidateResponse>> itunes(
            @Parameter(description = "검색어", example = "스타더스트 클럽 떠도는 별들을 위해")
            @RequestParam String term) {
        return ResponseDTO.okWithData(catalogCommand.lookupTracks(term).stream().map(TrackCandidateResponse::from).toList());
    }

    @Operation(summary = "앨범 저장 (병합)",
            description = "id 가 있으면 그 앨범을, 없으면 한국어 앨범명이 같은 앨범을 갱신하고 없으면 새로 만듭니다(새 앨범은 title 필수). "
                    + "곡 저장과 같이 보낸 키만 반영하며 releaseDate·itunesCollectionId 는 null 을 보내면 지웁니다. 커버는 커버 업로드로만 바뀝니다.")
    @PutMapping("/albums")
    public ResponseDTO<AlbumResponse> upsertAlbum(@RequestBody AlbumUpsertRequest request) {
        log.info("[관리자 앨범 저장 요청] id={}, title={}", request.id(), request.title() == null ? null : request.title().original());
        return ResponseDTO.okWithData(AlbumResponse.from(catalogCommand.upsertAlbum(request.toCommand())));
    }

    @Operation(summary = "앨범커버 업로드", description = "PNG 또는 JPEG, 5MB 이하. S3 albums/{albumId}.{ext} 에 저장하고 공개 URL 을 돌려줍니다. 다시 올리면 덮어씁니다. 이 앨범에 연결된 곡의 coverUrl 도 이 URL 이 됩니다.")
    @PutMapping(value = "/albums/{albumId}/cover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseDTO<CoverResponse> uploadCover(
            @Parameter(description = "앨범 ID", example = "3")
            @PathVariable Long albumId,
            @Parameter(description = "이미지 파일 (multipart 필드명 file)", example = "cover.png")
            @RequestPart("file") MultipartFile file) {
        log.info("[관리자 앨범커버 업로드 요청] albumId={}, filename={}, bytes={}", albumId, file.getOriginalFilename(), file.getSize());
        try {
            return ResponseDTO.okWithData(new CoverResponse(catalogCommand.uploadCover(albumId, file.getBytes())));
        } catch (IOException e) {
            throw new BadRequestException(ErrorCode.COVER_INVALID);
        }
    }
}
