package com.jsoonworld.notification.application.service;

import com.jsoonworld.notification.application.port.in.ManageSettingsUseCase;
import com.jsoonworld.notification.application.port.out.NotificationSettingsPort;
import com.jsoonworld.notification.domain.model.NotificationSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class ManageSettingsService implements ManageSettingsUseCase {

    private static final Logger log = LoggerFactory.getLogger(ManageSettingsService.class);

    private final NotificationSettingsPort settingsPort;

    public ManageSettingsService(NotificationSettingsPort settingsPort) {
        this.settingsPort = settingsPort;
    }

    @Override
    public Mono<NotificationSettings> getSettings(Long userId) {
        return settingsPort.findByUserId(userId)
                .defaultIfEmpty(NotificationSettings.defaultSettings(userId));
    }

    @Override
    public Mono<NotificationSettings> updateSettings(Long userId, boolean emailEnabled,
                                                      boolean slackEnabled, boolean pushEnabled,
                                                      boolean inAppEnabled) {
        NotificationSettings settings = new NotificationSettings(
                userId, emailEnabled, slackEnabled, pushEnabled, inAppEnabled);

        log.info("Updating notification settings for userId={}: email={}, slack={}, push={}, inApp={}",
                userId, emailEnabled, slackEnabled, pushEnabled, inAppEnabled);

        return settingsPort.save(settings);
    }
}
