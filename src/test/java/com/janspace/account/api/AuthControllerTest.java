package com.janspace.account.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.janspace.account.application.SocialLoginService;
import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

// 제공자 콜백은 브라우저 리다이렉트라 어떤 실패도 JSON 대신 프론트 error 리다이렉트로 끝나야 한다
class AuthControllerTest {

    final SocialLoginService service = mock(SocialLoginService.class);
    final AuthController controller = new AuthController(service, "http://front", false);

    @BeforeEach
    void bindRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void unbindRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("두 탭 첫 로그인 경쟁의 유니크 위반(DataIntegrityViolationException) -> error=INTERNAL_ERROR 리다이렉트")
    void unique_violation_redirects_with_internal_error() {
        when(service.handleCallback(any(), anyString(), anyString(), anyString()))
                .thenThrow(new DataIntegrityViolationException("Duplicate entry"));

        ResponseEntity<Void> res = controller.callback("naver", "provider-code", "state", null);

        assertThat(res.getStatusCode().value()).isEqualTo(302);
        assertThat(res.getHeaders().getLocation()).isEqualTo(URI.create("http://front/auth/callback?error=INTERNAL_ERROR"));
    }

    @Test
    @DisplayName("Redis 장애 -> error=INTERNAL_ERROR 리다이렉트")
    void redis_failure_redirects_with_internal_error() {
        when(service.handleCallback(any(), anyString(), anyString(), anyString()))
                .thenThrow(new RedisConnectionFailureException("down"));

        ResponseEntity<Void> res = controller.callback("google", "provider-code", "state", null);

        assertThat(res.getHeaders().getLocation()).isEqualTo(URI.create("http://front/auth/callback?error=INTERNAL_ERROR"));
    }
}
