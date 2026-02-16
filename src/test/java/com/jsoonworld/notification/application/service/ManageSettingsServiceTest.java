package com.jsoonworld.notification.application.service;

import com.jsoonworld.notification.application.port.out.NotificationSettingsPort;
import com.jsoonworld.notification.domain.model.NotificationSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManageSettingsServiceTest {

    @Mock
    private NotificationSettingsPort settingsPort;

    private ManageSettingsService service;

    @BeforeEach
    void setUp() {
        service = new ManageSettingsService(settingsPort);
    }

    @Test
    void getSettings_existingUser_returnsSettings() {
        Long userId = 1L;
        NotificationSettings existing = new NotificationSettings(userId, true, true, false, true);
        when(settingsPort.findByUserId(userId)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.getSettings(userId))
                .assertNext(settings -> {
                    assertThat(settings.userId()).isEqualTo(userId);
                    assertThat(settings.emailEnabled()).isTrue();
                    assertThat(settings.slackEnabled()).isTrue();
                    assertThat(settings.pushEnabled()).isFalse();
                    assertThat(settings.inAppEnabled()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void getSettings_newUser_returnsDefaults() {
        Long userId = 999L;
        when(settingsPort.findByUserId(userId)).thenReturn(Mono.empty());

        StepVerifier.create(service.getSettings(userId))
                .assertNext(settings -> {
                    assertThat(settings.userId()).isEqualTo(userId);
                    assertThat(settings.emailEnabled()).isTrue();
                    assertThat(settings.slackEnabled()).isFalse();
                    assertThat(settings.pushEnabled()).isFalse();
                    assertThat(settings.inAppEnabled()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void updateSettings_callsSaveOnPort() {
        Long userId = 1L;
        NotificationSettings saved = new NotificationSettings(userId, false, true, true, false);
        when(settingsPort.save(any(NotificationSettings.class))).thenReturn(Mono.just(saved));

        StepVerifier.create(service.updateSettings(userId, false, true, true, false))
                .assertNext(settings -> {
                    assertThat(settings.userId()).isEqualTo(userId);
                    assertThat(settings.emailEnabled()).isFalse();
                    assertThat(settings.slackEnabled()).isTrue();
                    assertThat(settings.pushEnabled()).isTrue();
                    assertThat(settings.inAppEnabled()).isFalse();
                })
                .verifyComplete();

        verify(settingsPort).save(any(NotificationSettings.class));
    }
}
