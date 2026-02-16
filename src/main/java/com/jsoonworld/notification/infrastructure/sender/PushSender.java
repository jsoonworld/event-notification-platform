package com.jsoonworld.notification.infrastructure.sender;

import com.jsoonworld.notification.application.port.out.NotificationSender;
import com.jsoonworld.notification.domain.model.DeliveryResult;
import com.jsoonworld.notification.domain.model.NotificationChannel;
import com.jsoonworld.notification.domain.model.NotificationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class PushSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(PushSender.class);

    @Override
    public Mono<DeliveryResult> send(NotificationRequest request) {
        log.info("Push notification stub: notificationId={}, userId={}",
            request.notificationId(), request.userId());
        return Mono.just(DeliveryResult.success(NotificationChannel.PUSH, request.notificationId()));
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.PUSH;
    }

    @Override
    public Mono<Boolean> isAvailable() {
        return Mono.just(false);
    }
}
