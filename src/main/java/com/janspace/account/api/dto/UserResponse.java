package com.janspace.account.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.janspace.account.application.UserProfile;
import com.janspace.account.domain.Departure;
import com.janspace.account.domain.SocialProvider;
import com.janspace.account.domain.User;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * 프론트 User 계약. providers 는 연결된 제공자 이름 배열, departure 는 미설정이면 null.
 */
@Schema(description = "사용자 프로필")
public record UserResponse(
        @Schema(description = "사용자 ID", example = "1024", requiredMode = Schema.RequiredMode.REQUIRED)
        Long id,
        @Schema(description = "닉네임", example = "잔나비 팬", requiredMode = Schema.RequiredMode.REQUIRED)
        String nickname,
        @Schema(description = "이메일 (제공자가 주지 않으면 null)", example = "fan@example.com",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String email,
        @Schema(description = "연결된 소셜 제공자 목록", example = "[\"NAVER\", \"GOOGLE\"]",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<String> providers,
        @Schema(description = "관리자 여부", example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("isAdmin") boolean isAdmin,
        @Schema(description = "약관 동의 시각 (UTC). 미동의면 null", example = "2026-09-24T09:00:00Z",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant consentedAt,
        @Schema(description = "출발지. 미설정이면 null", example = "{\"name\":\"서울 노원구\",\"countryCode\":\"KR\",\"lat\":37.654,\"lng\":127.056}",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        DepartureDto departure,
        @Schema(description = "표시 언어 (BCP-47)", example = "ko", requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"ko", "en", "zh-TW", "ja"})
        String lang,
        @Schema(description = "가입 시각 (UTC)", example = "2026-09-24T09:00:00Z",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant createdAt) {

    public static UserResponse from(UserProfile profile) {
        User u = profile.user();
        Departure d = u.getDeparture();
        return new UserResponse(u.getId(), u.getNickname(), u.getEmail(),
                profile.providers().stream().map(SocialProvider::name).toList(),
                u.isAdmin(), u.getConsentedAt(),
                d == null ? null : new DepartureDto(d.getName(), d.getCountryCode(), d.getLat().doubleValue(),
                        d.getLng().doubleValue()),
                u.getLocale().tag(), u.getCreatedAt());
    }
}
