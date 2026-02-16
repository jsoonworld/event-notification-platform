package com.jsoonworld.notification.infrastructure.sender;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsoonworld.notification.application.port.out.NotificationSender;
import com.jsoonworld.notification.domain.model.DeliveryResult;
import com.jsoonworld.notification.domain.model.NotificationChannel;
import com.jsoonworld.notification.domain.model.NotificationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Component
public class InAppSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(InAppSender.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final String CHANNEL_PREFIX = "notification:inapp:user:";

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public InAppSender(ReactiveRedisTemplate<String, String> redisTemplate,
                       ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<DeliveryResult> send(NotificationRequest request) {
        String channel = CHANNEL_PREFIX + request.userId();
        String payload;
        try {
            payload = objectMapper.writeValueAsString(request.toInAppPayload());
        } catch (Exception e) {
            log.error("Failed to serialize in-app payload: notificationId={}, error={}",
                request.notificationId(), e.getMessage());
            return Mono.just(DeliveryResult.failure(
                NotificationChannel.IN_APP, request.notificationId(),
                "SND_003: Serialization failed - " + e.getMessage()));
        }

        return redisTemplate.convertAndSend(channel, payload)
            .timeout(TIMEOUT)
            .map(subscriberCount -> {
                log.info("In-app notification published: notificationId={}, channel={}, subscribers={}",
                    request.notificationId(), channel, subscriberCount);
                return DeliveryResult.success(NotificationChannel.IN_APP, request.notificationId());
            })
            .onErrorResume(RedisConnectionFailureException.class, e -> {
                log.error("Redis connection failure: notificationId={}, error={}",
                    request.notificationId(), e.getMessage());
                return Mono.just(DeliveryResult.failure(
                    NotificationChannel.IN_APP, request.notificationId(),
                    "SND_003: Redis connection failure - " + e.getMessage()));
            })
            .onErrorResume(Exception.class, e -> {
                log.error("In-app send failed: notificationId={}, error={}",
                    request.notificationId(), e.getMessage());
                return Mono.just(DeliveryResult.failure(
                    NotificationChannel.IN_APP, request.notificationId(),
                    "SND_003: " + e.getMessage()));
            });
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.IN_APP;
    }
}
