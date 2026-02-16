package com.jsoonworld.notification.infrastructure.persistence.adapter;

import com.jsoonworld.notification.application.port.out.DeliveryStatsPort;
import com.jsoonworld.notification.infrastructure.persistence.repository.DeliveryStatsR2dbcRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDate;

@Component
public class DeliveryStatsAdapter implements DeliveryStatsPort {

    private final DeliveryStatsR2dbcRepository repository;

    public DeliveryStatsAdapter(DeliveryStatsR2dbcRepository repository) {
        this.repository = repository;
    }

    @Override
    public Mono<Void> incrementCount(LocalDate date, String channel, boolean success) {
        int sentCount = success ? 1 : 0;
        int failedCount = success ? 0 : 1;
        return repository.upsertCount(date, channel, sentCount, failedCount);
    }
}
