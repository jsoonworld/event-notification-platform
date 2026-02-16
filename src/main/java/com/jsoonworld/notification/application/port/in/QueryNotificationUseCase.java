package com.jsoonworld.notification.application.port.in;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

public interface QueryNotificationUseCase {

    Flux<Map<String, Object>> getNotificationsByUserId(Long userId, int page, int size);

    Mono<Long> countByUserId(Long userId);
}
