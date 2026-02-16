package com.jsoonworld.notification.infrastructure.persistence.repository;

import com.jsoonworld.notification.infrastructure.persistence.entity.NotificationSettingsEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface NotificationSettingsR2dbcRepository extends ReactiveCrudRepository<NotificationSettingsEntity, Long> {

    Mono<NotificationSettingsEntity> findByUserId(Long userId);
}
