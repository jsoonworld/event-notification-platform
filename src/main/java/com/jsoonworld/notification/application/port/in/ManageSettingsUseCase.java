package com.jsoonworld.notification.application.port.in;

import com.jsoonworld.notification.domain.model.NotificationSettings;
import reactor.core.publisher.Mono;

public interface ManageSettingsUseCase {

    Mono<NotificationSettings> getSettings(Long userId);

    Mono<NotificationSettings> updateSettings(Long userId, boolean emailEnabled, boolean slackEnabled,
                                               boolean pushEnabled, boolean inAppEnabled);
}
