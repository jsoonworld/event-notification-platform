package com.jsoonworld.notification.domain.model;

import java.util.Map;

public record NotificationRequest(
    String notificationId,
    String eventId,
    EventType eventType,
    Long userId,
    String recipient,
    String subject,
    String templateName,
    Map<String, Object> templateVariables
) {
    public Map<String, Object> toInAppPayload() {
        return Map.of(
            "notificationId", notificationId,
            "eventType", eventType.name(),
            "subject", subject,
            "variables", templateVariables
        );
    }
}
