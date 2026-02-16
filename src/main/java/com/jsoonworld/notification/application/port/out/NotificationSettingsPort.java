package com.jsoonworld.notification.application.port.out;

import com.jsoonworld.notification.domain.model.NotificationSettings;
import reactor.core.publisher.Mono;

public interface NotificationSettingsPort {

    Mono<NotificationSettings> findByUserId(Long userId);

    Mono<NotificationSettings> save(NotificationSettings settings);
}
