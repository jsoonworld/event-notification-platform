package com.jsoonworld.notification.domain.service;

import com.jsoonworld.notification.application.port.out.DeliveryStatsPort;
import com.jsoonworld.notification.application.port.out.NotificationLogPort;
import com.jsoonworld.notification.domain.model.DeliveryResult;
import com.jsoonworld.notification.domain.model.NotificationRequest;
import com.jsoonworld.notification.domain.model.NotificationStatus;
import reactor.core.publisher.Mono;

import java.time.LocalDate;

public class DeliveryTracker {

    private final NotificationLogPort notificationLogPort;
    private final DeliveryStatsPort deliveryStatsPort;

    public DeliveryTracker(NotificationLogPort notificationLogPort, DeliveryStatsPort deliveryStatsPort) {
        this.notificationLogPort = notificationLogPort;
        this.deliveryStatsPort = deliveryStatsPort;
    }

    public Mono<Void> track(NotificationRequest request, DeliveryResult result) {
        NotificationStatus status = result.isSuccess()
            ? NotificationStatus.SENT
            : NotificationStatus.FAILED;

        return notificationLogPort.save(
                request.notificationId(),
                request.eventId(),
                request.eventType().name(),
                request.userId(),
                result.channel().name(),
                status.name(),
                request.recipient(),
                result.errorMessage(),
                result.sentAt()
            )
            .then(deliveryStatsPort.incrementCount(
                LocalDate.now(),
                result.channel().name(),
                result.isSuccess()
            ));
    }
}
