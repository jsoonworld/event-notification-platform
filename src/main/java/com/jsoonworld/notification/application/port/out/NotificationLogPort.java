package com.jsoonworld.notification.application.port.out;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

public interface NotificationLogPort {

    Mono<Void> save(String notificationId, String eventId, String eventType,
                    Long userId, String channel, String status,
                    String recipient, String errorMessage, Instant sentAt);

    Flux<Map<String, Object>> findByUserId(Long userId, int offset, int limit);

    Mono<Long> countByUserId(Long userId);
}
