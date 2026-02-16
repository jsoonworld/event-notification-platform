package com.jsoonworld.notification.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsoonworld.notification.application.port.in.SendNotificationUseCase;
import com.jsoonworld.notification.application.port.out.ProcessedEventPort;
import com.jsoonworld.notification.domain.model.EventType;
import com.jsoonworld.notification.domain.model.NotificationRequest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubscriptionNotifConsumerTest {

    @Mock
    private ProcessedEventPort processedEventPort;

    @Mock
    private SendNotificationUseCase sendNotificationUseCase;

    @Mock
    private Acknowledgment acknowledgment;

    private SubscriptionNotifConsumer consumer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        EventDeserializer eventDeserializer = new EventDeserializer(objectMapper);
        consumer = new SubscriptionNotifConsumer(processedEventPort, sendNotificationUseCase, eventDeserializer);
    }

    @Test
    void consume_subscriptionRenewed_processesSuccessfully() throws Exception {
        String eventJson = objectMapper.writeValueAsString(Map.of(
            "eventId", "evt-sub-001",
            "eventType", "subscription.renewed",
            "payload", Map.of(
                "userId", 1,
                "email", "user@test.com",
                "subscriptionId", "SUB-001",
                "planName", "Premium",
                "amount", "29900"
            )
        ));

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
            "fluxpay.subscription.events", 0, 0, "key", eventJson);

        when(processedEventPort.existsByEventId("evt-sub-001")).thenReturn(Mono.just(false));
        when(sendNotificationUseCase.execute(any(NotificationRequest.class))).thenReturn(Mono.empty());
        when(processedEventPort.markAsProcessed(anyString(), anyString())).thenReturn(Mono.empty());

        consumer.consume(record, acknowledgment);

        // Allow async processing
        Thread.sleep(500);

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(sendNotificationUseCase).execute(captor.capture());

        NotificationRequest captured = captor.getValue();
        assertThat(captured.eventType()).isEqualTo(EventType.SUBSCRIPTION_RENEWED);
        assertThat(captured.subject()).isEqualTo("[moalog] 구독이 갱신되었습니다");
        assertThat(captured.templateName()).isEqualTo("email/subscription-renewed");
        assertThat(captured.recipient()).isEqualTo("user@test.com");
        assertThat(captured.templateVariables()).containsEntry("subscriptionId", "SUB-001");
        assertThat(captured.templateVariables()).containsEntry("planName", "Premium");

        verify(acknowledgment).acknowledge();
    }

    @Test
    void consume_subscriptionExpiring_processesSuccessfully() throws Exception {
        String eventJson = objectMapper.writeValueAsString(Map.of(
            "eventId", "evt-sub-002",
            "eventType", "subscription.expiring",
            "payload", Map.of(
                "userId", 2,
                "email", "user2@test.com",
                "subscriptionId", "SUB-002",
                "planName", "Basic",
                "nextBillingDate", "2026-03-01"
            )
        ));

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
            "fluxpay.subscription.events", 0, 0, "key", eventJson);

        when(processedEventPort.existsByEventId("evt-sub-002")).thenReturn(Mono.just(false));
        when(sendNotificationUseCase.execute(any(NotificationRequest.class))).thenReturn(Mono.empty());
        when(processedEventPort.markAsProcessed(anyString(), anyString())).thenReturn(Mono.empty());

        consumer.consume(record, acknowledgment);

        Thread.sleep(500);

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(sendNotificationUseCase).execute(captor.capture());

        NotificationRequest captured = captor.getValue();
        assertThat(captured.eventType()).isEqualTo(EventType.SUBSCRIPTION_EXPIRING);
        assertThat(captured.subject()).isEqualTo("[moalog] 구독 만료가 임박했습니다");
        assertThat(captured.templateName()).isEqualTo("email/subscription-expiring");
    }

    @Test
    void consume_subscriptionCancelled_usesGenericTemplate() throws Exception {
        String eventJson = objectMapper.writeValueAsString(Map.of(
            "eventId", "evt-sub-003",
            "eventType", "subscription.cancelled",
            "payload", Map.of(
                "userId", 3,
                "email", "user3@test.com",
                "subscriptionId", "SUB-003"
            )
        ));

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
            "fluxpay.subscription.events", 0, 0, "key", eventJson);

        when(processedEventPort.existsByEventId("evt-sub-003")).thenReturn(Mono.just(false));
        when(sendNotificationUseCase.execute(any(NotificationRequest.class))).thenReturn(Mono.empty());
        when(processedEventPort.markAsProcessed(anyString(), anyString())).thenReturn(Mono.empty());

        consumer.consume(record, acknowledgment);

        Thread.sleep(500);

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(sendNotificationUseCase).execute(captor.capture());

        NotificationRequest captured = captor.getValue();
        assertThat(captured.eventType()).isEqualTo(EventType.SUBSCRIPTION_CANCELLED);
        assertThat(captured.subject()).isEqualTo("[moalog] 구독이 해지되었습니다");
        assertThat(captured.templateName()).isEqualTo("email/generic-notification");
    }

    @Test
    void consume_duplicateEvent_skipsProcessing() throws Exception {
        String eventJson = objectMapper.writeValueAsString(Map.of(
            "eventId", "evt-dup-001",
            "eventType", "subscription.renewed",
            "payload", Map.of("userId", 1, "email", "user@test.com")
        ));

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
            "fluxpay.subscription.events", 0, 0, "key", eventJson);

        when(processedEventPort.existsByEventId("evt-dup-001")).thenReturn(Mono.just(true));

        consumer.consume(record, acknowledgment);

        Thread.sleep(500);

        verify(sendNotificationUseCase, never()).execute(any());
        verify(acknowledgment).acknowledge();
    }
}
