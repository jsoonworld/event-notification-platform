package com.jsoonworld.notification.interfaces.rest.dto.request;

import jakarta.validation.constraints.NotNull;

public record NotificationSettingsRequest(
        @NotNull Boolean emailEnabled,
        @NotNull Boolean slackEnabled,
        @NotNull Boolean pushEnabled,
        @NotNull Boolean inAppEnabled
) {
}
