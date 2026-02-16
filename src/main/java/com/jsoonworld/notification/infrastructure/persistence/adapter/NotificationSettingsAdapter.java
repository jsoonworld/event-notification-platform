package com.jsoonworld.notification.infrastructure.persistence.adapter;

import com.jsoonworld.notification.application.port.out.NotificationSettingsPort;
import com.jsoonworld.notification.domain.model.NotificationSettings;
import com.jsoonworld.notification.infrastructure.persistence.entity.NotificationSettingsEntity;
import com.jsoonworld.notification.infrastructure.persistence.repository.NotificationSettingsR2dbcRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class NotificationSettingsAdapter implements NotificationSettingsPort {

    private final NotificationSettingsR2dbcRepository repository;

    public NotificationSettingsAdapter(NotificationSettingsR2dbcRepository repository) {
        this.repository = repository;
    }

    @Override
    public Mono<NotificationSettings> findByUserId(Long userId) {
        return repository.findByUserId(userId)
            .map(this::toDomain);
    }

    @Override
    public Mono<NotificationSettings> save(NotificationSettings settings) {
        return repository.findByUserId(settings.userId())
            .flatMap(existing -> {
                existing.setEmailEnabled(settings.emailEnabled());
                existing.setSlackEnabled(settings.slackEnabled());
                existing.setPushEnabled(settings.pushEnabled());
                existing.setInAppEnabled(settings.inAppEnabled());
                existing.setUpdatedAt(java.time.Instant.now());
                return repository.save(existing);
            })
            .switchIfEmpty(Mono.defer(() -> {
                NotificationSettingsEntity entity = new NotificationSettingsEntity(
                    settings.userId(), settings.emailEnabled(), settings.slackEnabled(),
                    settings.pushEnabled(), settings.inAppEnabled()
                );
                return repository.save(entity);
            }))
            .map(this::toDomain);
    }

    private NotificationSettings toDomain(NotificationSettingsEntity entity) {
        return new NotificationSettings(
            entity.getUserId(),
            entity.getEmailEnabled(),
            entity.getSlackEnabled(),
            entity.getPushEnabled(),
            entity.getInAppEnabled()
        );
    }
}
