package com.janspace.bootstrap;

import com.janspace.account.application.AccountQuery;
import com.janspace.common.auth.CurrentUserArgumentResolver;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 조립. @CurrentUser 아규먼트 리졸버, API 진입·종료 로그 인터셉터, 동의 게이트 인터셉터를 등록한다.
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AccountQuery accountQuery;

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserArgumentResolver());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 로그 인터셉터를 먼저 둔다. 동의 게이트가 거부한 요청도 종료 로그(403)를 남긴다
        registry.addInterceptor(new RequestLoggingInterceptor()).addPathPatterns("/api/**");
        registry.addInterceptor(new ConsentInterceptor(accountQuery));
    }
}
