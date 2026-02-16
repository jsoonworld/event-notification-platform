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
public class PaymentNotifConsumer extends AbstractNotifConsumer {

    private static final String CONSUMER_GROUP = "notif-payment";
    private final EventDeserializer eventDeserializer;

    public PaymentNotifConsumer(ProcessedEventPort processedEventPort,
                                SendNotificationUseCase sendNotificationUseCase,
                                EventDeserializer eventDeserializer) {
        super(processedEventPort, sendNotificationUseCase);
        this.eventDeserializer = eventDeserializer;
    }

    @KafkaListener(
        topics = "fluxpay.payment.events",
        groupId = CONSUMER_GROUP,
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.info("Received payment event: topic={}, partition={}, offset={}",
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
            if (payload.has("paymentId")) templateVars.put("paymentId", payload.get("paymentId").asText());
            if (payload.has("orderId")) templateVars.put("orderId", payload.get("orderId").asText());
            if (payload.has("amount")) templateVars.put("amount", payload.get("amount").asText());
            if (payload.has("currency")) templateVars.put("currency", payload.get("currency").asText());
            if (payload.has("failureReason")) templateVars.put("failureReason", payload.get("failureReason").asText());
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
            case PAYMENT_APPROVED -> "[moalog] 결제가 완료되었습니다";
            case PAYMENT_FAILED -> "[moalog] 결제 처리에 실패했습니다";
            default -> "[moalog] 알림";
        };
    }

    private String resolveTemplateName(EventType eventType) {
        return switch (eventType) {
            case PAYMENT_APPROVED -> "email/payment-completed";
            case PAYMENT_FAILED -> "email/payment-failed";
            default -> "email/generic-notification";
        };
    }
}
