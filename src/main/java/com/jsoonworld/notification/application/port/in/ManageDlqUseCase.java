package com.jsoonworld.notification.application.port.in;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

public interface ManageDlqUseCase {

    Flux<Map<String, Object>> getDlqMessages(String status, int page, int size);

    Mono<Void> retryMessage(Long messageId);

    Mono<Void> discardMessage(Long messageId);
}
