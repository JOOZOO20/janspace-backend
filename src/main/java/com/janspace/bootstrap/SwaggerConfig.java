package com.janspace.bootstrap;

import com.janspace.common.constant.ApiVersion;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger 그룹(api-v1, test)과 BearerAuth 보안 스킴. Authorize 버튼에는 "Bearer " 없이 accessToken 만 넣는다 (api.md §3).
 */
@Configuration
public class SwaggerConfig {

    private static final String BEARER_AUTH = "BearerAuth";

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info().title("Janspace API").version("v1").description("잔스페이스 백엔드 API"))
                .components(new Components().addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }

    @Bean
    public GroupedOpenApi apiV1() {
        return GroupedOpenApi.builder()
                .group("api-v1")
                .pathsToMatch(ApiVersion.V1_BASE + "/**")
                .build();
    }

    // TEST_BASE 컨트롤러는 @Profile 로 운영에서 제외되므로 이 그룹은 로컬·개발에서만 내용이 있다
    @Bean
    public GroupedOpenApi testApi() {
        return GroupedOpenApi.builder()
                .group("test")
                .pathsToMatch(ApiVersion.TEST_BASE + "/**")
                .build();
    }
}
