package com.jsoonworld.notification.infrastructure.persistence.repository;

import com.jsoonworld.notification.infrastructure.persistence.entity.NotificationLogEntity;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface NotificationLogR2dbcRepository extends ReactiveCrudRepository<NotificationLogEntity, Long> {

    @Query("SELECT * FROM notification_log WHERE user_id = :userId ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    Flux<NotificationLogEntity> findByUserIdWithPaging(Long userId, int limit, int offset);

    @Query("SELECT COUNT(*) FROM notification_log WHERE user_id = :userId")
    Mono<Long> countByUserId(Long userId);

    Flux<NotificationLogEntity> findByEventId(String eventId);

    Flux<NotificationLogEntity> findByStatus(String status);
}
