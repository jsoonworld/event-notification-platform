package com.jsoonworld.notification.infrastructure.persistence.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsoonworld.notification.application.port.out.NotificationSettingsPort;
import com.jsoonworld.notification.domain.model.NotificationSettings;
import com.jsoonworld.notification.infrastructure.persistence.entity.NotificationSettingsEntity;
import com.jsoonworld.notification.infrastructure.persistence.repository.NotificationSettingsR2dbcRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Component
public class NotificationSettingsAdapter implements NotificationSettingsPort {

    private static final Logger log = LoggerFactory.getLogger(NotificationSettingsAdapter.class);
    private static final String CACHE_KEY_PREFIX = "notification:settings:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    private final NotificationSettingsR2dbcRepository repository;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public NotificationSettingsAdapter(NotificationSettingsR2dbcRepository repository,
                                       ReactiveRedisTemplate<String, String> redisTemplate,
                                       ObjectMapper objectMapper) {
        this.repository = repository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<NotificationSettings> findByUserId(Long userId) {
        String cacheKey = CACHE_KEY_PREFIX + userId;

        return redisTemplate.opsForValue().get(cacheKey)
                .flatMap(json -> {
                    try {
                        NotificationSettings settings = objectMapper.readValue(json, NotificationSettings.class);
                        log.debug("Cache hit for userId={}", userId);
                        return Mono.just(settings);
                    } catch (JsonProcessingException e) {
                        log.warn("Failed to deserialize cached settings for userId={}", userId, e);
                        return Mono.<NotificationSettings>empty();
                    }
                })
                .switchIfEmpty(Mono.defer(() -> findFromDbAndCache(userId, cacheKey)));
    }

    private Mono<NotificationSettings> findFromDbAndCache(Long userId, String cacheKey) {
        return repository.findByUserId(userId)
                .map(this::toDomain)
                .flatMap(settings -> cacheSettings(cacheKey, settings).thenReturn(settings))
                .doOnNext(s -> log.debug("Cache miss, loaded from DB for userId={}", userId));
    }

    private Mono<Boolean> cacheSettings(String cacheKey, NotificationSettings settings) {
        try {
            String json = objectMapper.writeValueAsString(settings);
            return redisTemplate.opsForValue().set(cacheKey, json, CACHE_TTL)
                    .onErrorResume(e -> {
                        log.warn("Failed to cache settings: {}", e.getMessage());
                        return Mono.just(false);
                    });
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize settings for caching", e);
            return Mono.just(false);
        }
    }

    @Override
    public Mono<NotificationSettings> save(NotificationSettings settings) {
        String cacheKey = CACHE_KEY_PREFIX + settings.userId();

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
                .map(this::toDomain)
                .flatMap(saved -> redisTemplate.delete(cacheKey)
                        .doOnNext(count -> log.debug("Invalidated cache for userId={}", settings.userId()))
                        .onErrorResume(e -> {
                            log.warn("Failed to invalidate cache for userId={}", settings.userId(), e);
                            return Mono.just(0L);
                        })
                        .thenReturn(saved));
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
