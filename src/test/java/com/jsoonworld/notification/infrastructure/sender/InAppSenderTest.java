package com.jsoonworld.notification.infrastructure.sender;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jsoonworld.notification.domain.model.DeliveryResult;
import com.jsoonworld.notification.domain.model.EventType;
import com.jsoonworld.notification.domain.model.NotificationChannel;
import com.jsoonworld.notification.domain.model.NotificationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InAppSenderTest {

    @Mock
    private ReactiveRedisTemplate<String, String> redisTemplate;

    private InAppSender inAppSender;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        inAppSender = new InAppSender(redisTemplate, objectMapper);
    }

    @Test
    void send_success_returnsSuccessResult() {
        String expectedChannel = "notification:inapp:user:1";
        when(redisTemplate.convertAndSend(eq(expectedChannel), anyString()))
            .thenReturn(Mono.just(1L));

        NotificationRequest request = createRequest();

        StepVerifier.create(inAppSender.send(request))
            .assertNext(result -> {
                assertThat(result.isSuccess()).isTrue();
                assertThat(result.channel()).isEqualTo(NotificationChannel.IN_APP);
                assertThat(result.notificationId()).isEqualTo("test-notif-id");
            })
            .verifyComplete();
    }

    @Test
    void send_redisConnectionFailure_returnsFailure() {
        String expectedChannel = "notification:inapp:user:1";
        when(redisTemplate.convertAndSend(eq(expectedChannel), anyString()))
            .thenReturn(Mono.error(new RedisConnectionFailureException("Connection refused")));

        NotificationRequest request = createRequest();

        StepVerifier.create(inAppSender.send(request))
            .assertNext(result -> {
                assertThat(result.isSuccess()).isFalse();
                assertThat(result.channel()).isEqualTo(NotificationChannel.IN_APP);
                assertThat(result.errorMessage()).contains("SND_003");
                assertThat(result.errorMessage()).contains("Redis connection failure");
            })
            .verifyComplete();
    }

    @Test
    void send_genericError_returnsFailure() {
        String expectedChannel = "notification:inapp:user:1";
        when(redisTemplate.convertAndSend(eq(expectedChannel), anyString()))
            .thenReturn(Mono.error(new RuntimeException("Unexpected error")));

        NotificationRequest request = createRequest();

        StepVerifier.create(inAppSender.send(request))
            .assertNext(result -> {
                assertThat(result.isSuccess()).isFalse();
                assertThat(result.channel()).isEqualTo(NotificationChannel.IN_APP);
                assertThat(result.errorMessage()).contains("SND_003");
            })
            .verifyComplete();
    }

    @Test
    void channel_returnsINAPP() {
        assertThat(inAppSender.channel()).isEqualTo(NotificationChannel.IN_APP);
    }

    private NotificationRequest createRequest() {
        return new NotificationRequest(
            "test-notif-id",
            "test-event-id",
            EventType.PAYMENT_APPROVED,
            1L,
            "user@test.com",
            "[moalog] Test",
            "email/payment-completed",
            Map.of("eventType", "PAYMENT_APPROVED")
        );
    }
}
