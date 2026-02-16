package com.jsoonworld.notification.domain.model;

import java.time.Instant;

public record DeliveryResult(
    NotificationChannel channel,
    String notificationId,
    boolean success,
    String errorMessage,
    Instant sentAt
) {
    public static DeliveryResult success(NotificationChannel channel, String notificationId) {
        return new DeliveryResult(channel, notificationId, true, null, Instant.now());
    }

    public static DeliveryResult failure(NotificationChannel channel, String notificationId, String errorMessage) {
        return new DeliveryResult(channel, notificationId, false, errorMessage, Instant.now());
    }

    public boolean isSuccess() {
        return success;
    }
}
