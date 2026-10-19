package com.stagelog.support;

import java.net.http.HttpClient;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 랜덤 포트의 앱을 부르는 얇은 HTTP 헬퍼. 리다이렉트를 따라가지 않고 4xx 도 예외 없이 응답으로 돌려준다.
 */
public class ApiClient {

    private static final Pattern REFRESH_COOKIE = Pattern.compile("refresh_token=([^;]*)");

    private final RestClient client;
    private final ObjectMapper objectMapper;

    public ApiClient(int port, ObjectMapper objectMapper) {
        HttpClient jdk = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        this.client = RestClient.builder().baseUrl("http://localhost:" + port)
                .requestFactory(new JdkClientHttpRequestFactory(jdk)).build();
        this.objectMapper = objectMapper;
    }

    public Res call(HttpMethod method, String uri, String bearer, String refreshCookie, String jsonBody) {
        RestClient.RequestBodySpec spec = client.method(method).uri(uri);
        if (bearer != null) {
            spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        }
        if (refreshCookie != null) {
            spec.header(HttpHeaders.COOKIE, "refresh_token=" + refreshCookie);
        }
        if (jsonBody != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(jsonBody);
        }
        return spec.exchange((req, res) -> {
            String body = res.bodyTo(String.class);
            JsonNode json = body == null || body.isBlank() ? null : objectMapper.readTree(body);
            return new Res(res.getStatusCode().value(), res.getHeaders(), body, json);
        });
    }

    public Res get(String uri, String bearer) {
        return call(HttpMethod.GET, uri, bearer, null, null);
    }

    public Res post(String uri, String bearer, String jsonBody) {
        return call(HttpMethod.POST, uri, bearer, null, jsonBody);
    }

    public record Res(int status, HttpHeaders headers, String body, JsonNode json) {

        public String code() {
            return json == null ? null : json.path("code").asString();
        }

        public JsonNode data() {
            return json == null ? null : json.path("data");
        }

        public String location() {
            return headers.getFirst(HttpHeaders.LOCATION);
        }

        public String setCookie() {
            return headers.getFirst(HttpHeaders.SET_COOKIE);
        }

        // Set-Cookie 의 refresh_token 값. 없으면 null
        public String refreshCookie() {
            String header = setCookie();
            if (header == null) {
                return null;
            }
            Matcher m = REFRESH_COOKIE.matcher(header);
            return m.find() ? m.group(1) : null;
        }
    }
}
