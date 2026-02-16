package com.jsoonworld.notification.interfaces.rest.dto.response;

import com.jsoonworld.notification.domain.model.NotificationSettings;

import java.time.Instant;

public record NotificationSettingsResponse(
        Long userId,
        boolean emailEnabled,
        boolean slackEnabled,
        boolean pushEnabled,
        boolean inAppEnabled,
        Instant updatedAt
) {
    public static NotificationSettingsResponse from(NotificationSettings settings) {
        return new NotificationSettingsResponse(
                settings.userId(),
                settings.emailEnabled(),
                settings.slackEnabled(),
                settings.pushEnabled(),
                settings.inAppEnabled(),
                Instant.now()
        );
    }
}
