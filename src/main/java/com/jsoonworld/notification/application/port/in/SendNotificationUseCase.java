package com.jsoonworld.notification.application.port.in;

import com.jsoonworld.notification.domain.model.NotificationRequest;
import reactor.core.publisher.Mono;

public interface SendNotificationUseCase {

    Mono<Void> execute(NotificationRequest request);
}
