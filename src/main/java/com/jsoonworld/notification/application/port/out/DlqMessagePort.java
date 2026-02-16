package com.jsoonworld.notification.application.port.out;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

public interface DlqMessagePort {

    Mono<Void> save(String originalTopic, String eventId, String payload,
                    String errorMessage);

    Flux<Map<String, Object>> findByStatus(String status, int offset, int limit);

    Mono<Void> updateStatus(Long id, String status);

    Mono<Void> incrementRetryCount(Long id);
}
