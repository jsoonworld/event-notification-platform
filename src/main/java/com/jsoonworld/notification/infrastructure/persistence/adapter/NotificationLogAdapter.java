package com.jsoonworld.notification.infrastructure.persistence.adapter;

import com.jsoonworld.notification.application.port.out.NotificationLogPort;
import com.jsoonworld.notification.infrastructure.persistence.entity.NotificationLogEntity;
import com.jsoonworld.notification.infrastructure.persistence.repository.NotificationLogR2dbcRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class NotificationLogAdapter implements NotificationLogPort {

    private final NotificationLogR2dbcRepository repository;

    public NotificationLogAdapter(NotificationLogR2dbcRepository repository) {
        this.repository = repository;
    }

    @Override
    public Mono<Void> save(String notificationId, String eventId, String eventType,
                           Long userId, String channel, String status,
                           String recipient, String errorMessage, Instant sentAt) {
        NotificationLogEntity entity = new NotificationLogEntity(
            notificationId, eventId, eventType, userId, channel, status,
            recipient, errorMessage, sentAt
        );
        return repository.save(entity).then();
    }

    @Override
    public Flux<Map<String, Object>> findByUserId(Long userId, int offset, int limit) {
        return repository.findByUserIdWithPaging(userId, limit, offset)
            .map(this::toMap);
    }

    @Override
    public Mono<Long> countByUserId(Long userId) {
        return repository.countByUserId(userId);
    }

    private Map<String, Object> toMap(NotificationLogEntity entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entity.getId());
        map.put("notificationId", entity.getNotificationId());
        map.put("eventId", entity.getEventId());
        map.put("eventType", entity.getEventType());
        map.put("userId", entity.getUserId());
        map.put("channel", entity.getChannel());
        map.put("status", entity.getStatus());
        map.put("recipient", entity.getRecipient());
        map.put("errorMessage", entity.getErrorMessage());
        map.put("sentAt", entity.getSentAt());
        map.put("createdAt", entity.getCreatedAt());
        return map;
    }
}
