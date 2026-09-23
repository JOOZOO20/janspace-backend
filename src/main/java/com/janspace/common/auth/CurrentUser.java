package com.janspace.common.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 컨트롤러 파라미터에 현재 인증 사용자(AuthenticatedUser)를 주입한다.
 * required=false 면 비로그인 요청에서 null 이 들어오고, 기본값(true)이면 401 UnauthorizedException 이 난다.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {

    boolean required() default true;
}
