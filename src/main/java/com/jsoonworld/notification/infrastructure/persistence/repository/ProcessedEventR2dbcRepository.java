package com.jsoonworld.notification.infrastructure.persistence.repository;

import com.jsoonworld.notification.infrastructure.persistence.entity.ProcessedEventEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface ProcessedEventR2dbcRepository extends ReactiveCrudRepository<ProcessedEventEntity, Long> {

    Mono<Boolean> existsByEventId(String eventId);
}
