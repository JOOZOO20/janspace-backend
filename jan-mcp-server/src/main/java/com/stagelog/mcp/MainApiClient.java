package com.stagelog.mcp;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 메인 앱 관리자 API 호출. 모든 요청에 서비스 계정 X-Api-Key 가 붙고, ResponseDTO 봉투({code, data, message})의 data 만 돌려준다.
 * 4xx·5xx 는 MainApiException 으로 바꿔 Tool 오류 결과가 되게 한다.
 */
@Component
@RequiredArgsConstructor
public class MainApiClient {

    public static final String API_KEY_HEADER = "X-Api-Key";
    public static final String CLIENT_HEADER = "X-Mcp-Client";

    private final RestClient mainApiRestClient;
    private final ObjectMapper objectMapper;

    /** 메인 앱이 오류로 답했다. code·message 는 메인 앱 ErrorCode 봉투의 값이다. */
    public static class MainApiException extends RuntimeException {

        private final int status;
        private final String code;

        public MainApiException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }
    }

    public JsonNode get(String path, Map<String, ?> query, Object... pathVariables) {
        return unwrap(mainApiRestClient.get()
                .uri(builder -> withQuery(builder.path(path), query).build(pathVariables))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw error(response.getStatusCode().value(), new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
                })
                .body(String.class));
    }

    public JsonNode post(String path, Object body, String client) {
        return unwrap(mainApiRestClient.post()
                .uri(path)
                .header(CLIENT_HEADER, client)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(body))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw error(response.getStatusCode().value(), new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
                })
                .body(String.class));
    }

    private static UriBuilder withQuery(UriBuilder builder, Map<String, ?> query) {
        query.forEach((name, value) -> {
            if (value != null) {
                builder.queryParam(name, value);
            }
        });
        return builder;
    }

    private JsonNode unwrap(String body) {
        return objectMapper.readTree(body).path("data");
    }

    private MainApiException error(int status, String body) {
        JsonNode json;
        try {
            json = objectMapper.readTree(body);
        } catch (RuntimeException e) {
            return new MainApiException(status, "UNKNOWN", "메인 API 가 봉투가 아닌 응답을 돌려줬습니다");
        }
        return new MainApiException(status, json.path("code").asString("UNKNOWN"), json.path("message").asString(""));
    }
}
