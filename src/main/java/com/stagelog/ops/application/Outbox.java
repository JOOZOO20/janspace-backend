package com.stagelog.ops.application;

import com.stagelog.ops.domain.OutboxEvent;
import com.stagelog.ops.domain.OutboxEventRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Transactional Outbox 공개 계약. 업무 데이터를 쓰는 트랜잭션 안에서 발행할 메시지를 기록한다(MANDATORY: 트랜잭션 밖 호출은 예외).
 * 커밋되면 OutboxRelay 가 SQS 로 발행하고, 롤백되면 메시지도 없다. 본문은 payload 의 필드에 messageId·createdAt 을 더한 JSON 이다.
 */
@Service
@RequiredArgsConstructor
public class Outbox {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final OutboxEventRepository repository;

    // payload 는 JSON 객체로 직렬화되는 값(record 등). 만든 messageId 를 돌려준다
    @Transactional(propagation = Propagation.MANDATORY)
    public String append(String queue, Object payload) {
        String messageId = UUID.randomUUID().toString();
        ObjectNode body = MAPPER.createObjectNode();
        body.put("messageId", messageId);
        body.setAll((ObjectNode) MAPPER.valueToTree(payload));
        body.put("createdAt", Instant.now().toString());
        repository.save(new OutboxEvent(messageId, queue, MAPPER.writeValueAsString(body)));
        return messageId;
    }

    // 본문의 messageId. JSON 이 아니거나 없으면 null
    public static String messageIdOf(String body) {
        try {
            JsonNode id = MAPPER.readTree(body).get("messageId");
            return id == null || id.isNull() ? null : id.asString();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
