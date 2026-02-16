package com.jsoonworld.notification.application.service;

import com.jsoonworld.notification.application.port.in.SendNotificationUseCase;
import com.jsoonworld.notification.application.port.out.NotificationSender;
import com.jsoonworld.notification.application.port.out.NotificationSettingsPort;
import com.jsoonworld.notification.domain.model.NotificationChannel;
import com.jsoonworld.notification.domain.model.NotificationRequest;
import com.jsoonworld.notification.domain.model.NotificationSettings;
import com.jsoonworld.notification.domain.service.DeliveryTracker;
import com.jsoonworld.notification.domain.service.NotificationRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class SendNotificationService implements SendNotificationUseCase {

    private static final Logger log = LoggerFactory.getLogger(SendNotificationService.class);

    private final NotificationRouter notificationRouter;
    private final DeliveryTracker deliveryTracker;
    private final NotificationSettingsPort notificationSettingsPort;
    private final Map<NotificationChannel, NotificationSender> senderMap;

    public SendNotificationService(NotificationRouter notificationRouter,
                                    DeliveryTracker deliveryTracker,
                                    NotificationSettingsPort notificationSettingsPort,
                                    List<NotificationSender> senders) {
        this.notificationRouter = notificationRouter;
        this.deliveryTracker = deliveryTracker;
        this.notificationSettingsPort = notificationSettingsPort;
        this.senderMap = senders.stream()
            .collect(Collectors.toMap(NotificationSender::channel, Function.identity()));
    }

    @Override
    public Mono<Void> execute(NotificationRequest request) {
        return notificationSettingsPort.findByUserId(request.userId())
            .defaultIfEmpty(NotificationSettings.defaultSettings(request.userId()))
            .flatMapMany(settings -> {
                List<NotificationChannel> channels = notificationRouter.resolveChannels(
                    request.eventType(), settings
                );
                log.info("Resolved channels for eventType={}, userId={}: {}",
                    request.eventType(), request.userId(), channels);

                return Flux.fromIterable(channels)
                    .filter(senderMap::containsKey)
                    .flatMap(channel -> {
                        NotificationSender sender = senderMap.get(channel);
                        return sender.send(request)
                            .flatMap(result -> deliveryTracker.track(request, result)
                                .thenReturn(result));
                    });
            })
            .then();
    }
}
