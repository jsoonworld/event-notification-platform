package com.jsoonworld.notification.infrastructure.sender;

import com.jsoonworld.notification.domain.model.EventType;
import com.jsoonworld.notification.domain.model.NotificationChannel;
import com.jsoonworld.notification.domain.model.NotificationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PushSenderTest {

    private PushSender pushSender;

    @BeforeEach
    void setUp() {
        pushSender = new PushSender();
    }

    @Test
    void send_stub_returnsSuccess() {
        NotificationRequest request = new NotificationRequest(
            "test-notif-id",
            "test-event-id",
            EventType.PAYMENT_APPROVED,
            1L,
            "user@test.com",
            "[moalog] Test",
            "email/payment-completed",
            Map.of("eventType", "PAYMENT_APPROVED")
        );

        StepVerifier.create(pushSender.send(request))
            .assertNext(result -> {
                assertThat(result.isSuccess()).isTrue();
                assertThat(result.channel()).isEqualTo(NotificationChannel.PUSH);
                assertThat(result.notificationId()).isEqualTo("test-notif-id");
            })
            .verifyComplete();
    }

    @Test
    void isAvailable_returnsFalse() {
        StepVerifier.create(pushSender.isAvailable())
            .assertNext(available -> assertThat(available).isFalse())
            .verifyComplete();
    }

    @Test
    void channel_returnsPUSH() {
        assertThat(pushSender.channel()).isEqualTo(NotificationChannel.PUSH);
    }
}
