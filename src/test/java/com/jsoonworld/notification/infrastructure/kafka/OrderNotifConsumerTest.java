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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderNotifConsumerTest {

    @Mock
    private ProcessedEventPort processedEventPort;

    @Mock
    private SendNotificationUseCase sendNotificationUseCase;

    @Mock
    private Acknowledgment acknowledgment;

    private OrderNotifConsumer consumer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        EventDeserializer eventDeserializer = new EventDeserializer(objectMapper);
        consumer = new OrderNotifConsumer(processedEventPort, sendNotificationUseCase, eventDeserializer);
    }

    @Test
    void consume_orderCreated_processesSuccessfully() throws Exception {
        String eventJson = objectMapper.writeValueAsString(Map.of(
            "eventId", "evt-ord-001",
            "eventType", "order.created",
            "payload", Map.of(
                "userId", 1,
                "email", "user@test.com",
                "orderId", "ORD-001",
                "amount", "50000",
                "currency", "KRW",
                "orderStatus", "CREATED"
            )
        ));

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
            "fluxpay.order.events", 0, 0, "key", eventJson);

        when(processedEventPort.existsByEventId("evt-ord-001")).thenReturn(Mono.just(false));
        when(sendNotificationUseCase.execute(any(NotificationRequest.class))).thenReturn(Mono.empty());
        when(processedEventPort.markAsProcessed(anyString(), anyString())).thenReturn(Mono.empty());

        consumer.consume(record, acknowledgment);

        Thread.sleep(500);

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(sendNotificationUseCase).execute(captor.capture());

        NotificationRequest captured = captor.getValue();
        assertThat(captured.eventType()).isEqualTo(EventType.ORDER_CREATED);
        assertThat(captured.subject()).isEqualTo("[moalog] 주문이 접수되었습니다");
        assertThat(captured.templateName()).isEqualTo("email/generic-notification");
        assertThat(captured.templateVariables()).containsEntry("orderId", "ORD-001");
        assertThat(captured.templateVariables()).containsEntry("amount", "50000");

        verify(acknowledgment).acknowledge();
    }

    @Test
    void consume_orderCompleted_processesSuccessfully() throws Exception {
        String eventJson = objectMapper.writeValueAsString(Map.of(
            "eventId", "evt-ord-002",
            "eventType", "order.completed",
            "payload", Map.of(
                "userId", 2,
                "email", "user2@test.com",
                "orderId", "ORD-002",
                "amount", "30000",
                "currency", "KRW"
            )
        ));

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
            "fluxpay.order.events", 0, 0, "key", eventJson);

        when(processedEventPort.existsByEventId("evt-ord-002")).thenReturn(Mono.just(false));
        when(sendNotificationUseCase.execute(any(NotificationRequest.class))).thenReturn(Mono.empty());
        when(processedEventPort.markAsProcessed(anyString(), anyString())).thenReturn(Mono.empty());

        consumer.consume(record, acknowledgment);

        Thread.sleep(500);

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(sendNotificationUseCase).execute(captor.capture());

        NotificationRequest captured = captor.getValue();
        assertThat(captured.eventType()).isEqualTo(EventType.ORDER_COMPLETED);
        assertThat(captured.subject()).isEqualTo("[moalog] 주문이 완료되었습니다");
        assertThat(captured.templateName()).isEqualTo("email/generic-notification");
    }

    @Test
    void consume_orderCancelled_processesSuccessfully() throws Exception {
        String eventJson = objectMapper.writeValueAsString(Map.of(
            "eventId", "evt-ord-003",
            "eventType", "order.cancelled",
            "payload", Map.of(
                "userId", 3,
                "email", "user3@test.com",
                "orderId", "ORD-003",
                "amount", "10000",
                "currency", "KRW"
            )
        ));

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
            "fluxpay.order.events", 0, 0, "key", eventJson);

        when(processedEventPort.existsByEventId("evt-ord-003")).thenReturn(Mono.just(false));
        when(sendNotificationUseCase.execute(any(NotificationRequest.class))).thenReturn(Mono.empty());
        when(processedEventPort.markAsProcessed(anyString(), anyString())).thenReturn(Mono.empty());

        consumer.consume(record, acknowledgment);

        Thread.sleep(500);

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(sendNotificationUseCase).execute(captor.capture());

        NotificationRequest captured = captor.getValue();
        assertThat(captured.eventType()).isEqualTo(EventType.ORDER_CANCELLED);
        assertThat(captured.subject()).isEqualTo("[moalog] 주문이 취소되었습니다");
    }
}
