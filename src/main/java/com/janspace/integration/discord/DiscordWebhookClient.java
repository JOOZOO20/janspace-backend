package com.janspace.integration.discord;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 디스코드 웹훅 클라이언트. {"content": ...} 를 POST 한다. 웹훅 URL 이 비어 있으면(키 미발급) 보내지 않고 INFO 만 남긴다.
 * HTTP 실패는 RestClient 예외(RuntimeException)를 그대로 던지며, 삼키는 것은 호출자(ops OperatorAlert)의 몫이다.
 */
@Slf4j
@Component
public class DiscordWebhookClient {

    // 디스코드 메시지 content 최대 길이
    public static final int MAX_CONTENT_LENGTH = 2000;

    private final RestClient restClient;
    private final String webhookUrl;

    public DiscordWebhookClient(RestClient.Builder restClientBuilder,
                                @Value("${janspace.discord.webhook-url:}") String webhookUrl) {
        this.restClient = restClientBuilder.build();
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
    }

    public void send(String content) {
        // 1. URL 미설정이면 건너뛴다. 유일하게 허용된 "키 없음" 동작
        if (webhookUrl.isEmpty()) {
            log.info("[디스코드 알림 건너뜀] DISCORD_WEBHOOK_URL 미설정. length={}", content == null ? 0 : content.length());
            return;
        }
        // 2. 디스코드 제한(2000자)에 맞춰 자른다
        String body = content == null ? "" : content;
        if (body.length() > MAX_CONTENT_LENGTH) {
            body = body.substring(0, MAX_CONTENT_LENGTH);
        }
        // 3. 외부 호출. 웹훅 URL 은 비밀값이므로 로그에 남기지 않는다
        restClient.post()
                .uri(webhookUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("content", body))
                .retrieve()
                .toBodilessEntity();
        log.info("[디스코드 알림 전송] length={}", body.length());
    }
}
