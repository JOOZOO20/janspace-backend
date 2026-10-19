package com.stagelog.setlist.infrastructure;

import com.stagelog.common.constant.ApiVersion;
import com.stagelog.common.exception.ApplicationException;
import com.stagelog.common.exception.ErrorCode;
import com.stagelog.integration.youtube.YouTubeClient;
import com.stagelog.integration.youtube.YouTubeTokenResponse;
import com.stagelog.setlist.application.port.YouTubeOAuthPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/**
 * YouTubeOAuthPort 구현. redirect_uri = {api-base-url}/api/v1/admin/youtube/callback.
 */
@Component
public class YouTubeOAuthAdapter implements YouTubeOAuthPort {

    public static final String CALLBACK_PATH = ApiVersion.V1_BASE + "/admin/youtube/callback";

    private final YouTubeClient client;
    private final String redirectUri;

    public YouTubeOAuthAdapter(YouTubeClient client, @Value("${stagelog.api-base-url}") String apiBaseUrl) {
        this.client = client;
        String base = apiBaseUrl.endsWith("/") ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1) : apiBaseUrl;
        this.redirectUri = base + CALLBACK_PATH;
    }

    @Override
    public String authorizeUrl(String state) {
        return client.authorizeUrl(redirectUri, state);
    }

    @Override
    public String exchangeCode(String code) {
        try {
            YouTubeTokenResponse token = client.exchangeCode(code, redirectUri);
            if (token == null || token.refreshToken() == null || token.refreshToken().isBlank()) {
                throw new ApplicationException(ErrorCode.YOUTUBE_API_ERROR, "구글이 리프레시 토큰을 주지 않았습니다. 동의 화면을 다시 거쳐야 합니다.");
            }
            return token.refreshToken();
        } catch (RestClientException e) {
            throw new ApplicationException(ErrorCode.YOUTUBE_API_ERROR, e);
        }
    }
}
