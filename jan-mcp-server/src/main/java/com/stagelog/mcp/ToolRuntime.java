package com.stagelog.mcp;

import com.stagelog.mcp.MainApiClient.MainApiException;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 모든 Tool 호출의 공통 처리: 클라이언트별 분당 호출 제한, 응답 직렬화와 Redaction, 호출 로그(Tool 이름, 입력, 응답 크기, 소요 시간, 클라이언트).
 * 입력 오류(IllegalArgumentException)와 메인 API 오류는 MCP 오류 결과(isError)로 돌려 클라이언트가 고쳐 다시 부를 수 있게 한다.
 */
@Slf4j
@Component
public class ToolRuntime {

    private static final long WINDOW_NANOS = 60_000_000_000L;

    private final ObjectMapper objectMapper;
    private final int limitPerMinute;
    // ponytail: 인스턴스 메모리 sliding window. MCP 서버를 여러 대로 늘리면 Redis 같은 공유 저장소로 옮긴다
    private final Map<String, Deque<Long>> calls = new HashMap<>();

    public ToolRuntime(ObjectMapper objectMapper, McpProperties properties) {
        this.objectMapper = objectMapper;
        this.limitPerMinute = properties.rateLimitPerMinute();
    }

    public CallToolResult call(String tool, McpSyncServerExchange exchange, Map<String, Object> arguments,
                               BiFunction<Map<String, Object>, String, Object> body) {
        long started = System.nanoTime();
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        String client = String.valueOf(exchange.transportContext().get(McpServerConfig.CLIENT_CONTEXT_KEY));
        String outcome = "OK";
        String text;
        boolean error = false;
        if (!tryAcquire(client, started)) {
            outcome = "RATE_LIMITED";
            text = "호출 제한 초과: 클라이언트당 분당 " + limitPerMinute + "회까지 부를 수 있습니다. 잠시 후 다시 시도하세요.";
            error = true;
        } else {
            try {
                text = Redaction.redact(objectMapper.writeValueAsString(body.apply(args, client)));
            } catch (IllegalArgumentException e) {
                outcome = "INVALID_INPUT";
                text = "입력 오류: " + e.getMessage();
                error = true;
            } catch (MainApiException e) {
                outcome = "MAIN_API_" + e.status();
                text = Redaction.redact("메인 API 오류 " + e.status() + " " + e.code() + ": " + e.getMessage());
                error = true;
            } catch (RuntimeException e) {
                outcome = "INTERNAL_ERROR";
                text = "MCP 서버 내부 오류로 조회하지 못했습니다.";
                error = true;
                log.error("[MCP Tool 실패] tool={}, client={}", tool, client, e);
            }
        }
        log.info("[MCP Tool 호출] tool={}, client={}, input={}, responseBytes={}, elapsedMs={}, outcome={}", tool, client,
                Redaction.redact(objectMapper.writeValueAsString(args)), text.getBytes(StandardCharsets.UTF_8).length,
                (System.nanoTime() - started) / 1_000_000, outcome);
        return CallToolResult.builder().addTextContent(text).isError(error).build();
    }

    private synchronized boolean tryAcquire(String client, long now) {
        Deque<Long> window = calls.computeIfAbsent(client, key -> new ArrayDeque<>());
        while (!window.isEmpty() && now - window.peekFirst() >= WINDOW_NANOS) {
            window.pollFirst();
        }
        if (window.size() >= limitPerMinute) {
            return false;
        }
        window.addLast(now);
        return true;
    }
}
