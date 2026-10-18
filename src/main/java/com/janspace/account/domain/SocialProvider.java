package com.janspace.account.domain;

import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.BadRequestException;
import java.util.Locale;

/**
 * 지원하는 소셜 로그인 제공자. URL 경로 세그먼트(naver, google)와 enum 이름을 오간다.
 */
public enum SocialProvider {

    NAVER,
    GOOGLE;

    public String path() {
        return name().toLowerCase(Locale.ROOT);
    }

    // 경로 세그먼트가 미지의 값이면 400 UNSUPPORTED_PROVIDER
    public static SocialProvider fromPath(String path) {
        for (SocialProvider p : values()) {
            if (p.path().equalsIgnoreCase(path)) {
                return p;
            }
        }
        throw new BadRequestException(ErrorCode.UNSUPPORTED_PROVIDER);
    }
}
