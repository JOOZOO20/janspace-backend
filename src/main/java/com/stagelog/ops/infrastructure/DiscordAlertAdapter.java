package com.stagelog.ops.infrastructure;

import com.stagelog.integration.discord.DiscordWebhookClient;
import com.stagelog.ops.application.port.AlertPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * AlertPort 의 디스코드 구현.
 */
@Component
@RequiredArgsConstructor
public class DiscordAlertAdapter implements AlertPort {

    private final DiscordWebhookClient discordWebhookClient;

    @Override
    public void send(String message) {
        discordWebhookClient.send(message);
    }
}
