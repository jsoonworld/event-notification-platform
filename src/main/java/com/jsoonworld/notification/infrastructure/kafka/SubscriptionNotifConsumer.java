package com.jsoonworld.notification.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsoonworld.notification.application.port.in.SendNotificationUseCase;
import com.jsoonworld.notification.application.port.out.ProcessedEventPort;
import com.jsoonworld.notification.domain.model.EventType;
import com.jsoonworld.notification.domain.model.NotificationRequest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class SubscriptionNotifConsumer extends AbstractNotifConsumer {

    private static final String CONSUMER_GROUP = "notif-subscription";
    private final EventDeserializer eventDeserializer;

    public SubscriptionNotifConsumer(ProcessedEventPort processedEventPort,
                                      SendNotificationUseCase sendNotificationUseCase,
                                      EventDeserializer eventDeserializer) {
        super(processedEventPort, sendNotificationUseCase);
        this.eventDeserializer = eventDeserializer;
    }

    @KafkaListener(
        topics = "fluxpay.subscription.events",
        groupId = CONSUMER_GROUP,
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.info("Received subscription event: topic={}, partition={}, offset={}",
            record.topic(), record.partition(), record.offset());
        handleRecord(record, ack);
    }

    @Override
    protected String extractEventId(ConsumerRecord<String, String> record) {
        JsonNode node = eventDeserializer.deserialize(record.value());
        return eventDeserializer.extractEventId(node);
    }

    @Override
    protected Mono<NotificationRequest> processEvent(ConsumerRecord<String, String> record) {
        return Mono.fromCallable(() -> {
            JsonNode node = eventDeserializer.deserialize(record.value());
            String eventId = eventDeserializer.extractEventId(node);
            String eventTypeStr = eventDeserializer.extractEventType(node);
            EventType eventType = resolveEventType(eventTypeStr);
            JsonNode payload = eventDeserializer.extractPayload(node);

            Long userId = payload.has("userId") ? payload.get("userId").asLong() : 0L;
            String recipient = payload.has("email") ? payload.get("email").asText() : "";
            String subject = resolveSubject(eventType);
            String templateName = resolveTemplateName(eventType);

            Map<String, Object> templateVars = new HashMap<>();
            if (payload.has("subscriptionId")) templateVars.put("subscriptionId", payload.get("subscriptionId").asText());
            if (payload.has("planName")) templateVars.put("planName", payload.get("planName").asText());
            if (payload.has("nextBillingDate")) templateVars.put("nextBillingDate", payload.get("nextBillingDate").asText());
            if (payload.has("amount")) templateVars.put("amount", payload.get("amount").asText());
            templateVars.put("eventType", eventType.name());

            return new NotificationRequest(
                UUID.randomUUID().toString(),
                eventId,
                eventType,
                userId,
                recipient,
                subject,
                templateName,
                templateVars
            );
        });
    }

    @Override
    protected EventType resolveEventType(String eventType) {
        return EventType.fromEventTypeString(eventType);
    }

    @Override
    protected String consumerGroup() {
        return CONSUMER_GROUP;
    }

    private String resolveSubject(EventType eventType) {
        return switch (eventType) {
            case SUBSCRIPTION_RENEWED -> "[moalog] 구독이 갱신되었습니다";
            case SUBSCRIPTION_EXPIRING -> "[moalog] 구독 만료가 임박했습니다";
            case SUBSCRIPTION_CANCELLED -> "[moalog] 구독이 해지되었습니다";
            default -> "[moalog] 알림";
        };
    }

    private String resolveTemplateName(EventType eventType) {
        return switch (eventType) {
            case SUBSCRIPTION_RENEWED -> "email/subscription-renewed";
            case SUBSCRIPTION_EXPIRING -> "email/subscription-expiring";
            case SUBSCRIPTION_CANCELLED -> "email/generic-notification";
            default -> "email/generic-notification";
        };
    }
}
