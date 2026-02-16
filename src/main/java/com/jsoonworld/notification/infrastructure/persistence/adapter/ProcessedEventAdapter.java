package com.jsoonworld.notification.infrastructure.persistence.adapter;

import com.jsoonworld.notification.application.port.out.ProcessedEventPort;
import com.jsoonworld.notification.infrastructure.persistence.entity.ProcessedEventEntity;
import com.jsoonworld.notification.infrastructure.persistence.repository.ProcessedEventR2dbcRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class ProcessedEventAdapter implements ProcessedEventPort {

    private final ProcessedEventR2dbcRepository repository;

    public ProcessedEventAdapter(ProcessedEventR2dbcRepository repository) {
        this.repository = repository;
    }

    @Override
    public Mono<Boolean> existsByEventId(String eventId) {
        return repository.existsByEventId(eventId);
    }

    @Override
    public Mono<Void> markAsProcessed(String eventId, String consumerGroup) {
        ProcessedEventEntity entity = new ProcessedEventEntity(eventId, consumerGroup);
        return repository.save(entity).then();
    }
}
