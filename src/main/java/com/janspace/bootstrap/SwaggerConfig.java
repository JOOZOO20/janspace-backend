package com.janspace.bootstrap;

import com.janspace.common.auth.CurrentUser;
import com.janspace.common.constant.ApiVersion;
import com.janspace.common.locale.LocalizedText;
import com.janspace.common.locale.SupportedLocale;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springdoc.core.models.GroupedOpenApi;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.method.HandlerMethod;

/**
 * Swagger 그룹(api-v1, api-v2, api-v3, test)과 BearerAuth 보안 스킴. Authorize 버튼에는 "Bearer " 없이 accessToken 만 넣는다.
 * 자물쇠는 전역이 아니라 인증이 필요한 operation 에만 붙인다. 공개 경로(PublicEndpoints)는 자물쇠 없이 호출된다.
 */
@Configuration
public class SwaggerConfig {

    private static final String BEARER_AUTH = "BearerAuth";

    static {
        SpringDocUtils.getConfig()
                // @CurrentUser 는 토큰에서 주입되므로 요청 파라미터로 문서화하지 않는다
                .addAnnotationsToIgnore(CurrentUser.class)
                // LocalizedText 는 로케일 키를 @JsonAnyGetter 로 펼치므로 어노테이션만으로는 키가 문서에 안 나온다
                .replaceWithSchema(LocalizedText.class, localizedTextSchema());
    }

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info().title("Janspace API").version("v1").description("잔스페이스 백엔드 API"))
                .components(new Components().addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")));
    }

    @Bean
    public GroupedOpenApi apiV1() {
        return group("api-v1", ApiVersion.V1_BASE);
    }

    // V2·V3 는 예약 버전이라 지금은 비어 있다. 마이그레이션 때 컨트롤러만 옮기면 그룹이 채워진다
    @Bean
    public GroupedOpenApi apiV2() {
        return group("api-v2", ApiVersion.V2_BASE);
    }

    @Bean
    public GroupedOpenApi apiV3() {
        return group("api-v3", ApiVersion.V3_BASE);
    }

    // TEST_BASE 컨트롤러는 @Profile 로 운영에서 제외되므로 이 그룹은 로컬·개발에서만 내용이 있다
    @Bean
    public GroupedOpenApi testApi() {
        return group("test", ApiVersion.TEST_BASE);
    }

    private static GroupedOpenApi group(String name, String base) {
        return GroupedOpenApi.builder()
                .group(name)
                .pathsToMatch(base + "/**")
                .addOperationCustomizer(SwaggerConfig::securityFromCurrentUser)
                .addOpenApiCustomizer(SwaggerConfig::securityFromPathRules)
                .build();
    }

    // 1. @CurrentUser 를 받는 핸들러는 GET 이라도 토큰이 필요하다. required=false 면 토큰이 선택이므로 빈 요구({})를 함께 둔다
    static Operation securityFromCurrentUser(Operation operation, HandlerMethod handler) {
        for (MethodParameter parameter : handler.getMethodParameters()) {
            CurrentUser currentUser = parameter.getParameterAnnotation(CurrentUser.class);
            if (currentUser != null) {
                if (!currentUser.required()) {
                    operation.addSecurityItem(new SecurityRequirement());
                }
                operation.addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
                return operation;
            }
        }
        return operation;
    }

    // 2. 나머지는 보안 규칙으로 판정한다. 공개 경로가 아니면(쓰기, /admin) 자물쇠를 붙인다
    static void securityFromPathRules(OpenAPI openApi) {
        if (openApi.getPaths() == null) {
            return;
        }
        openApi.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
            if (operation.getSecurity() == null && !PublicEndpoints.isPublic(method.name(), path)) {
                operation.addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
            }
        }));
    }

    private static Schema<?> localizedTextSchema() {
        ObjectSchema schema = new ObjectSchema();
        schema.description("원어와 로케일별 번역. 번역이 있는 로케일 키만 포함된다");
        schema.addProperty("original", new StringSchema().description("원어 텍스트").example("잔나비 단독 공연"));
        Map<String, String> example = new LinkedHashMap<>();
        example.put("original", "잔나비 단독 공연");
        for (SupportedLocale locale : SupportedLocale.values()) {
            String text = exampleText(locale);
            schema.addProperty(locale.tag(), new StringSchema().description(locale.tag() + " 번역. 없으면 키가 빠진다").example(text));
            example.put(locale.tag(), text);
        }
        schema.required(List.of("original"));
        schema.example(example);
        return schema;
    }

    private static String exampleText(SupportedLocale locale) {
        return switch (locale) {
            case KO -> "잔나비 단독 공연";
            case EN -> "JANNABI Solo Concert";
            case ZH_TW -> "JANNABI 單獨演唱會";
            case JA -> "JANNABI 単独公演";
        };
    }
}
