package com.jsoonworld.notification.application.port.out;

import com.jsoonworld.notification.domain.model.DeliveryResult;
import com.jsoonworld.notification.domain.model.NotificationChannel;
import com.jsoonworld.notification.domain.model.NotificationRequest;
import reactor.core.publisher.Mono;

public interface NotificationSender {

    Mono<DeliveryResult> send(NotificationRequest request);

    NotificationChannel channel();

    default Mono<Boolean> isAvailable() {
        return Mono.just(true);
    }
}
