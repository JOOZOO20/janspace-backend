package com.janspace.bootstrap;

import com.janspace.common.auth.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 컨트롤러 진입·종료 INFO 로그를 한 곳에서 남긴다. 요청 ID 는 로그 패턴이 MDC 에서 붙인다.
 * 경로는 쿼리스트링 없이 남긴다. OAuth 콜백의 code·state 같은 일회용 비밀이 쿼리에 실리기 때문이다.
 */
@Slf4j
public class RequestLoggingInterceptor implements HandlerInterceptor {

    private static final String START_NANOS = RequestLoggingInterceptor.class.getName() + ".start";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_NANOS, System.nanoTime());
        log.info("[API 진입] method={}, path={}, handler={}, userId={}",
                request.getMethod(), request.getRequestURI(), handlerName(handler), currentUserId());
        return true;
    }

    // 예외도 전역 핸들러가 응답을 쓴 뒤에 불리므로 status 는 최종 응답 코드다
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        long elapsedMs = (System.nanoTime() - (Long) request.getAttribute(START_NANOS)) / 1_000_000;
        log.info("[API 종료] method={}, path={}, handler={}, userId={}, status={}, elapsedMs={}",
                request.getMethod(), request.getRequestURI(), handlerName(handler), currentUserId(),
                response.getStatus(), elapsedMs);
    }

    private static String handlerName(Object handler) {
        return handler instanceof HandlerMethod method
                ? method.getBeanType().getSimpleName() + "." + method.getMethod().getName()
                : handler.getClass().getSimpleName();
    }

    private static Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user ? user.userId() : null;
    }
}
