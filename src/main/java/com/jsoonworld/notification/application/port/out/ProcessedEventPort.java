package com.jsoonworld.notification.application.port.out;

import reactor.core.publisher.Mono;

public interface ProcessedEventPort {

    Mono<Boolean> existsByEventId(String eventId);

    Mono<Void> markAsProcessed(String eventId, String consumerGroup);
}
