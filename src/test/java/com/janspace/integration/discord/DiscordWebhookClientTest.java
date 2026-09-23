package com.janspace.integration.discord;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.hamcrest.Matchers.hasLength;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.janspace.ops.application.OperatorAlert;
import com.janspace.ops.infrastructure.DiscordAlertAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 디스코드 웹훅 클라이언트를 MockRestServiceServer 로 검증한다 (Spring 컨텍스트 없음). 실제 웹훅 호출은 실키가 없어 미확인.
 */
class DiscordWebhookClientTest {

    static final String URL = "https://discord.example/api/webhooks/1/abc";

    RestClient.Builder builder;
    MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
    }

    @Test
    @DisplayName("{\"content\": ...} JSON 을 웹훅 URL 로 POST 한다")
    void posts_content_json() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"content\":\"행사 시각 미입력 3건\"}"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        new DiscordWebhookClient(builder, URL).send("행사 시각 미입력 3건");

        server.verify();
    }

    @Test
    @DisplayName("2000자를 넘는 메시지는 2000자로 잘라 보낸다")
    void truncates_to_discord_limit() {
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.content", hasLength(DiscordWebhookClient.MAX_CONTENT_LENGTH)))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        new DiscordWebhookClient(builder, URL).send("가".repeat(DiscordWebhookClient.MAX_CONTENT_LENGTH + 500));

        server.verify();
    }

    @Test
    @DisplayName("웹훅이 5xx 를 돌려줘도 OperatorAlert 는 예외를 밖으로 내지 않는다")
    void operator_alert_swallows_http_failure() {
        server.expect(requestTo(URL)).andRespond(withServerError());
        OperatorAlert alert = new OperatorAlert(new DiscordAlertAdapter(new DiscordWebhookClient(builder, URL)));

        assertThatCode(() -> alert.send("실패해도 조용히")).doesNotThrowAnyException();

        server.verify();
    }

    @Test
    @DisplayName("웹훅 URL 이 비어 있으면 요청 없이 건너뛴다")
    void skips_when_url_is_empty() {
        // 기대 요청을 하나도 등록하지 않았으므로 호출이 있으면 AssertionError 가 난다
        new DiscordWebhookClient(builder, " ").send("건너뜀");
        new DiscordWebhookClient(builder, null).send("건너뜀");

        server.verify();
    }
}
