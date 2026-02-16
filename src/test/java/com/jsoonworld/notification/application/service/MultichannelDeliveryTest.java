package com.jsoonworld.notification.application.service;

import com.jsoonworld.notification.application.port.out.NotificationSender;
import com.jsoonworld.notification.application.port.out.NotificationSettingsPort;
import com.jsoonworld.notification.domain.model.*;
import com.jsoonworld.notification.domain.service.DeliveryTracker;
import com.jsoonworld.notification.domain.service.NotificationRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MultichannelDeliveryTest {

    @Mock
    private NotificationSettingsPort settingsPort;

    @Mock
    private DeliveryTracker deliveryTracker;

    private SendNotificationService service;

    @Mock
    private NotificationSender emailSender;

    @Mock
    private NotificationSender slackSender;

    @Mock
    private NotificationSender inAppSender;

    @BeforeEach
    void setUp() {
        when(emailSender.channel()).thenReturn(NotificationChannel.EMAIL);
        when(slackSender.channel()).thenReturn(NotificationChannel.SLACK);
        when(inAppSender.channel()).thenReturn(NotificationChannel.IN_APP);

        NotificationRouter router = new NotificationRouter();
        service = new SendNotificationService(
            router, deliveryTracker, settingsPort,
            List.of(emailSender, slackSender, inAppSender)
        );
    }

    @Test
    void paymentFailed_sendsToEmailInAppAndSlack() {
        Long userId = 1L;
        NotificationSettings settings = new NotificationSettings(userId, true, true, false, true);
        when(settingsPort.findByUserId(userId)).thenReturn(Mono.just(settings));

        DeliveryResult emailResult = DeliveryResult.success(NotificationChannel.EMAIL, "notif-1");
        DeliveryResult slackResult = DeliveryResult.success(NotificationChannel.SLACK, "notif-1");
        DeliveryResult inAppResult = DeliveryResult.success(NotificationChannel.IN_APP, "notif-1");

        when(emailSender.send(any())).thenReturn(Mono.just(emailResult));
        when(slackSender.send(any())).thenReturn(Mono.just(slackResult));
        when(inAppSender.send(any())).thenReturn(Mono.just(inAppResult));
        when(deliveryTracker.track(any(), any())).thenReturn(Mono.empty());

        NotificationRequest request = new NotificationRequest(
            "notif-1", "evt-1", EventType.PAYMENT_FAILED, userId,
            "user@test.com", "[moalog] 결제 실패", "email/payment-failed",
            Map.of("eventType", "PAYMENT_FAILED")
        );

        StepVerifier.create(service.execute(request))
            .verifyComplete();

        verify(emailSender).send(any());
        verify(slackSender).send(any());
        verify(inAppSender).send(any());
        verify(deliveryTracker, times(3)).track(any(), any());
    }

    @Test
    void userDisablesSlack_skipsSlackChannel() {
        Long userId = 2L;
        NotificationSettings settings = new NotificationSettings(userId, true, false, false, true);
        when(settingsPort.findByUserId(userId)).thenReturn(Mono.just(settings));

        DeliveryResult emailResult = DeliveryResult.success(NotificationChannel.EMAIL, "notif-2");
        DeliveryResult inAppResult = DeliveryResult.success(NotificationChannel.IN_APP, "notif-2");

        when(emailSender.send(any())).thenReturn(Mono.just(emailResult));
        when(inAppSender.send(any())).thenReturn(Mono.just(inAppResult));
        when(deliveryTracker.track(any(), any())).thenReturn(Mono.empty());

        NotificationRequest request = new NotificationRequest(
            "notif-2", "evt-2", EventType.PAYMENT_FAILED, userId,
            "user@test.com", "[moalog] 결제 실패", "email/payment-failed",
            Map.of("eventType", "PAYMENT_FAILED")
        );

        StepVerifier.create(service.execute(request))
            .verifyComplete();

        verify(emailSender).send(any());
        verify(inAppSender).send(any());
        verify(slackSender, never()).send(any());
        verify(deliveryTracker, times(2)).track(any(), any());
    }

    @Test
    void paymentApproved_sendsToEmailAndInApp() {
        Long userId = 3L;
        NotificationSettings settings = new NotificationSettings(userId, true, true, false, true);
        when(settingsPort.findByUserId(userId)).thenReturn(Mono.just(settings));

        DeliveryResult emailResult = DeliveryResult.success(NotificationChannel.EMAIL, "notif-3");
        DeliveryResult inAppResult = DeliveryResult.success(NotificationChannel.IN_APP, "notif-3");

        when(emailSender.send(any())).thenReturn(Mono.just(emailResult));
        when(inAppSender.send(any())).thenReturn(Mono.just(inAppResult));
        when(deliveryTracker.track(any(), any())).thenReturn(Mono.empty());

        NotificationRequest request = new NotificationRequest(
            "notif-3", "evt-3", EventType.PAYMENT_APPROVED, userId,
            "user@test.com", "[moalog] 결제 완료", "email/payment-completed",
            Map.of("eventType", "PAYMENT_APPROVED")
        );

        StepVerifier.create(service.execute(request))
            .verifyComplete();

        verify(emailSender).send(any());
        verify(inAppSender).send(any());
        verify(slackSender, never()).send(any());
    }
}
