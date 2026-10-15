package com.janspace.account.api;

import static com.janspace.common.constant.ApiVersion.V1_BASE;

import com.janspace.account.api.dto.ConsentRequest;
import com.janspace.account.api.dto.DepartureDto;
import com.janspace.account.api.dto.LinkCompleteRequest;
import com.janspace.account.api.dto.LinkStartRequest;
import com.janspace.account.api.dto.LinkStartResponse;
import com.janspace.account.api.dto.UpdateMeRequest;
import com.janspace.account.api.dto.UserResponse;
import com.janspace.account.application.AccountLinkService;
import com.janspace.account.application.ProfileService;
import com.janspace.account.domain.Departure;
import com.janspace.account.domain.SocialProvider;
import com.janspace.common.auth.AuthenticatedUser;
import com.janspace.common.auth.CurrentUser;
import com.janspace.common.dto.ResponseDTO;
import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.BadRequestException;
import com.janspace.common.locale.SupportedLocale;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 내 프로필: 조회·동의·수정·프로필 사진·계정 연결·해제·탈퇴. 모두 로그인 필수.
 */
@Tag(name = "내 정보 (Me)", description = "프로필 조회·수정, 프로필 사진, 약관 동의, 소셜 계정 연결·해제, 탈퇴")
@RestController
@RequestMapping(V1_BASE + "/me")
@RequiredArgsConstructor
public class MeController {

    private final ProfileService profileService;
    private final AccountLinkService accountLinkService;

    @Operation(summary = "내 프로필 조회", description = "액세스 토큰의 사용자 프로필을 돌려줍니다.")
    @GetMapping
    public ResponseDTO<UserResponse> me(@CurrentUser AuthenticatedUser user) {
        return ResponseDTO.okWithData(UserResponse.from(profileService.me(user.userId())));
    }

    @Operation(summary = "약관 동의", description = "동의 시각과 약관 버전을 기록합니다. 동의 전에는 쓰기 API 가 CONSENT_REQUIRED 로 거부됩니다.")
    @PostMapping("/consent")
    public ResponseDTO<UserResponse> consent(@CurrentUser AuthenticatedUser user,
                                             @RequestBody @Valid ConsentRequest request) {
        return ResponseDTO.okWithData(UserResponse.from(profileService.consent(user.userId(), request.termsVersion())));
    }

    @Operation(summary = "프로필 부분 수정", description = "닉네임·표시 언어·출발지 중 보낸 필드만 바꿉니다. 출발지는 이 API 로만 설정합니다.")
    @PatchMapping
    public ResponseDTO<UserResponse> update(@CurrentUser AuthenticatedUser user,
                                            @RequestBody @Valid UpdateMeRequest request) {
        SupportedLocale locale = null;
        if (request.lang() != null) {
            locale = SupportedLocale.fromTag(request.lang())
                    .orElseThrow(() -> new BadRequestException(ErrorCode.INVALID_INPUT, "지원하지 않는 언어입니다: " + request.lang()));
        }
        DepartureDto d = request.departure();
        Departure departure = d == null ? null : new Departure(d.name(), d.countryCode(), d.lat(), d.lng());
        return ResponseDTO.okWithData(UserResponse.from(
                profileService.update(user.userId(), request.nickname(), locale, departure)));
    }

    @Operation(summary = "소셜 계정 연결 시작", description = "PKCE code_challenge 를 받아 연결 모드 state 를 만들고 제공자 인가 URL 을 돌려줍니다. 같은 제공자가 이미 연결돼 있으면 409 입니다.")
    @PostMapping("/providers/{provider}")
    public ResponseDTO<LinkStartResponse> startLink(
            @CurrentUser AuthenticatedUser user,
            @Parameter(description = "제공자 (naver, google)", example = "google") @PathVariable String provider,
            @RequestBody @Valid LinkStartRequest request) {
        SocialProvider socialProvider = SocialProvider.fromPath(provider);
        String url = accountLinkService.start(user.userId(), socialProvider, request.codeChallenge(),
                AuthController.redirectUri(socialProvider));
        return ResponseDTO.okWithData(new LinkStartResponse(url));
    }

    @Operation(summary = "소셜 계정 연결 완료", description = "연결 코드와 PKCE verifier 를 검증하고 현재 사용자에게 소셜 계정을 부착합니다. 타인 소유 409 SOCIAL_ACCOUNT_ALREADY_LINKED, 같은 제공자 중복 409 PROVIDER_ALREADY_LINKED.")
    @PostMapping("/providers/{provider}/complete")
    public ResponseDTO<UserResponse> completeLink(
            @CurrentUser AuthenticatedUser user,
            @Parameter(description = "제공자 (naver, google)", example = "google") @PathVariable String provider,
            @RequestBody @Valid LinkCompleteRequest request) {
        accountLinkService.complete(user.userId(), SocialProvider.fromPath(provider), request.code(), request.codeVerifier());
        return ResponseDTO.okWithData(UserResponse.from(profileService.me(user.userId())));
    }

    @Operation(summary = "소셜 계정 연결 해제",
            description = "내 계정에서 해당 제공자 연결을 지웁니다. 연결돼 있지 않으면 404 PROVIDER_NOT_LINKED, 남은 로그인 수단이 이것 하나면 "
                    + "409 CANNOT_UNLINK_LAST_PROVIDER. 해제한 소셜 계정으로 다시 로그인하면 새 사용자로 가입됩니다.")
    @DeleteMapping("/providers/{provider}")
    public ResponseDTO<UserResponse> unlink(
            @CurrentUser AuthenticatedUser user,
            @Parameter(description = "제공자 (naver, google)", example = "google") @PathVariable String provider) {
        accountLinkService.unlink(user.userId(), SocialProvider.fromPath(provider));
        return ResponseDTO.okWithData(UserResponse.from(profileService.me(user.userId())));
    }

    @Operation(summary = "프로필 사진 업로드",
            description = "multipart: image 파트(PNG/JPEG, 5MB 이하, 긴 변 1024px 이하 — 아니면 400 AVATAR_IMAGE_INVALID). 서버는 줄이지 않으므로 "
                    + "클라이언트가 잘라 줄여서 보냅니다. 이전 사진은 지워지고 avatarUrl 이 새 주소로 바뀝니다.")
    @PutMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseDTO<UserResponse> uploadAvatar(
            @CurrentUser AuthenticatedUser user,
            @Parameter(description = "이미지 파일 (multipart 필드명 image)", example = "avatar.png")
            @RequestPart("image") MultipartFile image) {
        byte[] bytes;
        try {
            bytes = image.getBytes();
        } catch (IOException e) {
            throw new BadRequestException(ErrorCode.AVATAR_IMAGE_INVALID);
        }
        return ResponseDTO.okWithData(UserResponse.from(profileService.changeAvatar(user.userId(), bytes)));
    }

    @Operation(summary = "프로필 사진 삭제", description = "사진을 지워 avatarUrl 을 null 로 만듭니다(클라이언트는 기본 그림 표시). 사진이 없어도 200 입니다.")
    @DeleteMapping("/avatar")
    public ResponseDTO<UserResponse> deleteAvatar(@CurrentUser AuthenticatedUser user) {
        return ResponseDTO.okWithData(UserResponse.from(profileService.deleteAvatar(user.userId())));
    }

    @Operation(summary = "탈퇴", description = "사용자를 소프트 삭제하고 소셜 연결·리프레시 토큰·프로필 사진을 모두 지웁니다. 같은 소셜 계정으로 다시 로그인하면 새 사용자입니다.")
    @DeleteMapping
    public ResponseDTO<Void> withdraw(@CurrentUser AuthenticatedUser user) {
        profileService.withdraw(user.userId());
        return ResponseDTO.ok();
    }
}
