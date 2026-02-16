package com.jsoonworld.notification.domain.service;

import com.jsoonworld.notification.domain.model.EventType;
import com.jsoonworld.notification.domain.model.NotificationChannel;
import com.jsoonworld.notification.domain.model.NotificationSettings;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class NotificationRouter {

    private static final Map<EventType, Set<NotificationChannel>> DEFAULT_CHANNELS = Map.ofEntries(
        Map.entry(EventType.PAYMENT_APPROVED, Set.of(
            NotificationChannel.EMAIL, NotificationChannel.IN_APP
        )),
        Map.entry(EventType.PAYMENT_FAILED, Set.of(
            NotificationChannel.EMAIL, NotificationChannel.IN_APP, NotificationChannel.SLACK
        )),
        Map.entry(EventType.SUBSCRIPTION_RENEWED, Set.of(
            NotificationChannel.EMAIL
        )),
        Map.entry(EventType.SUBSCRIPTION_EXPIRING, Set.of(
            NotificationChannel.EMAIL, NotificationChannel.IN_APP
        )),
        Map.entry(EventType.SUBSCRIPTION_CANCELLED, Set.of(
            NotificationChannel.EMAIL, NotificationChannel.IN_APP
        )),
        Map.entry(EventType.ORDER_CREATED, Set.of(
            NotificationChannel.EMAIL, NotificationChannel.IN_APP
        )),
        Map.entry(EventType.ORDER_COMPLETED, Set.of(
            NotificationChannel.EMAIL
        )),
        Map.entry(EventType.REFUND_COMPLETED, Set.of(
            NotificationChannel.EMAIL, NotificationChannel.IN_APP
        )),
        Map.entry(EventType.REFUND_FAILED, Set.of(
            NotificationChannel.EMAIL, NotificationChannel.IN_APP, NotificationChannel.SLACK
        ))
    );

    public List<NotificationChannel> resolveChannels(EventType eventType, NotificationSettings settings) {
        Set<NotificationChannel> defaults = DEFAULT_CHANNELS.getOrDefault(eventType, Set.of());

        if (settings == null) {
            return List.copyOf(defaults);
        }

        return defaults.stream()
            .filter(settings::isChannelEnabled)
            .toList();
    }
}
