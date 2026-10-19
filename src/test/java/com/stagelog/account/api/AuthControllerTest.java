package com.stagelog.account.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stagelog.account.application.SocialLoginService;
import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

// 제공자 콜백은 브라우저 리다이렉트라 어떤 실패도 JSON 대신 프론트 error 리다이렉트로 끝나야 한다
@ExtendWith(OutputCaptureExtension.class)
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

    @Test
    @DisplayName("제공자 error 값은 개행 등 제어 문자를 _ 로 바꾸고 100자까지만 로그에 남긴다 (로그 줄 위조 방지)")
    void provider_error_is_logged_on_one_line_and_truncated(CapturedOutput output) {
        String forged = "access_denied\r\n[FORGED] 가짜 로그" + "x".repeat(500);

        ResponseEntity<Void> res = controller.callback("naver", null, "state", forged);

        assertThat(res.getHeaders().getLocation()).isEqualTo(URI.create("http://front/auth/callback?error=OAUTH_PROVIDER_ERROR"));
        assertThat(output.getOut()).contains("error=access_denied__[FORGED] 가짜 로그x")
                .doesNotContain("x".repeat(101));
        assertThat(AuthController.loggable("a\tb")).isEqualTo("a_b");
        assertThat(AuthController.loggable(null)).isNull();
    }
}
