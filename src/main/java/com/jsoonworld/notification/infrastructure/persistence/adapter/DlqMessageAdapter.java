package com.jsoonworld.notification.infrastructure.persistence.adapter;

import com.jsoonworld.notification.application.port.out.DlqMessagePort;
import com.jsoonworld.notification.infrastructure.persistence.entity.DlqMessageEntity;
import com.jsoonworld.notification.infrastructure.persistence.repository.DlqMessageR2dbcRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class DlqMessageAdapter implements DlqMessagePort {

    private final DlqMessageR2dbcRepository repository;

    public DlqMessageAdapter(DlqMessageR2dbcRepository repository) {
        this.repository = repository;
    }

    @Override
    public Mono<Void> save(String originalTopic, String eventId, String payload, String errorMessage) {
        DlqMessageEntity entity = new DlqMessageEntity(originalTopic, eventId, payload, errorMessage);
        return repository.save(entity).then();
    }

    @Override
    public Flux<Map<String, Object>> findByStatus(String status, int offset, int limit) {
        return repository.findByStatusWithPaging(status, limit, offset)
            .map(this::toMap);
    }

    @Override
    public Mono<Void> updateStatus(Long id, String status) {
        return repository.updateStatus(id, status);
    }

    @Override
    public Mono<Void> incrementRetryCount(Long id) {
        return repository.incrementRetryCount(id);
    }

    private Map<String, Object> toMap(DlqMessageEntity entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entity.getId());
        map.put("originalTopic", entity.getOriginalTopic());
        map.put("eventId", entity.getEventId());
        map.put("payload", entity.getPayload());
        map.put("errorMessage", entity.getErrorMessage());
        map.put("retryCount", entity.getRetryCount());
        map.put("status", entity.getStatus());
        map.put("createdAt", entity.getCreatedAt());
        map.put("retriedAt", entity.getRetriedAt());
        return map;
    }
}
