package com.stagelog.ops.infrastructure;

import com.stagelog.ops.application.port.MessageQueuePort;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.InvalidMessageContentsException;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/**
 * MessageQueuePort 의 SQS 구현. Queue URL 은 이름으로 한 번 조회해 기억한다.
 */
@Component
@RequiredArgsConstructor
public class SqsMessageQueueAdapter implements MessageQueuePort {

    private final SqsClient sqs;
    private final Map<String, String> urls = new ConcurrentHashMap<>();

    @Override
    public void send(String queue, String body) {
        try {
            sqs.sendMessage(b -> b.queueUrl(url(queue)).messageBody(body));
        } catch (InvalidMessageContentsException e) {
            throw new RejectedException("SQS 가 메시지 본문을 거부했습니다. queue=" + queue, e);
        }
    }

    @Override
    public List<Received> receive(String queue, int max, int waitSeconds, Integer visibilitySeconds) {
        ReceiveMessageRequest.Builder request = ReceiveMessageRequest.builder().queueUrl(url(queue))
                .maxNumberOfMessages(max).waitTimeSeconds(waitSeconds)
                .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT);
        if (visibilitySeconds != null) {
            request.visibilityTimeout(visibilitySeconds);
        }
        return sqs.receiveMessage(request.build()).messages().stream()
                .map(m -> new Received(m.messageId(), m.receiptHandle(), m.body(),
                        Integer.parseInt(m.attributes().getOrDefault(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, "1"))))
                .toList();
    }

    @Override
    public void changeVisibility(String queue, String receiptHandle, int seconds) {
        sqs.changeMessageVisibility(b -> b.queueUrl(url(queue)).receiptHandle(receiptHandle).visibilityTimeout(seconds));
    }

    @Override
    public void delete(String queue, String receiptHandle) {
        sqs.deleteMessage(b -> b.queueUrl(url(queue)).receiptHandle(receiptHandle));
    }

    @Override
    public void ensureQueue(String queue, String deadLetterQueue, int visibilityTimeoutSeconds, int maxReceiveCount) {
        String dlqUrl = sqs.createQueue(b -> b.queueName(deadLetterQueue)).queueUrl();
        String dlqArn = sqs.getQueueAttributes(b -> b.queueUrl(dlqUrl).attributeNames(QueueAttributeName.QUEUE_ARN))
                .attributes().get(QueueAttributeName.QUEUE_ARN);
        String redrive = "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"" + maxReceiveCount + "\"}";
        Map<QueueAttributeName, String> attributes = Map.of(
                QueueAttributeName.VISIBILITY_TIMEOUT, String.valueOf(visibilityTimeoutSeconds),
                QueueAttributeName.REDRIVE_POLICY, redrive);
        // 이미 있는 Queue 에 다른 속성으로 CreateQueue 하면 오류이므로 이름으로 만들고 속성은 따로 맞춘다
        String url = sqs.createQueue(b -> b.queueName(queue)).queueUrl();
        sqs.setQueueAttributes(b -> b.queueUrl(url).attributes(attributes));
        urls.put(queue, url);
        urls.put(deadLetterQueue, dlqUrl);
    }

    private String url(String queue) {
        return urls.computeIfAbsent(queue, name -> {
            try {
                return sqs.getQueueUrl(b -> b.queueName(name)).queueUrl();
            } catch (QueueDoesNotExistException e) {
                throw new IllegalStateException("SQS Queue 가 없습니다. queue=" + name, e);
            }
        });
    }
}
