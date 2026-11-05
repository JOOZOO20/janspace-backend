package com.stagelog.support;

import com.stagelog.common.auth.AuthenticatedUser;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithSecurityContext;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

/**
 * JWT 필터와 같은 관리자 principal(AuthenticatedUser + ADMIN 권한)로 테스트를 돌린다. @CurrentUser 로 관리자 ID 를 받는 API 용.
 * 쓰기 요청은 동의 게이트가 users 행을 조회하므로 ID 의 동의한 사용자 행이 먼저 있어야 한다.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@WithSecurityContext(factory = WithAdmin.Factory.class)
public @interface WithAdmin {

    long ID = 990_001L;

    class Factory implements WithSecurityContextFactory<WithAdmin> {

        @Override
        public SecurityContext createSecurityContext(WithAdmin annotation) {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(new AuthenticatedUser(ID, true), null,
                    List.of(new SimpleGrantedAuthority("ADMIN"))));
            return context;
        }
    }
}
