package com.jsoonworld.notification.domain.model;

public record NotificationSettings(
    Long userId,
    boolean emailEnabled,
    boolean slackEnabled,
    boolean pushEnabled,
    boolean inAppEnabled
) {
    public boolean isChannelEnabled(NotificationChannel channel) {
        return switch (channel) {
            case EMAIL -> emailEnabled;
            case SLACK -> slackEnabled;
            case PUSH -> pushEnabled;
            case IN_APP -> inAppEnabled;
        };
    }

    public static NotificationSettings defaultSettings(Long userId) {
        return new NotificationSettings(userId, true, false, false, true);
    }
}
