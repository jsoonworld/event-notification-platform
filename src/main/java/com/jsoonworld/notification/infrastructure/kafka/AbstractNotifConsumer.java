package com.jsoonworld.notification.infrastructure.kafka;

import com.jsoonworld.notification.application.port.in.SendNotificationUseCase;
import com.jsoonworld.notification.application.port.out.ProcessedEventPort;
import com.jsoonworld.notification.domain.model.EventType;
import com.jsoonworld.notification.domain.model.NotificationRequest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.support.Acknowledgment;
import reactor.core.publisher.Mono;

public abstract class AbstractNotifConsumer {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    private final ProcessedEventPort processedEventPort;
    private final SendNotificationUseCase sendNotificationUseCase;

    protected AbstractNotifConsumer(ProcessedEventPort processedEventPort,
                                     SendNotificationUseCase sendNotificationUseCase) {
        this.processedEventPort = processedEventPort;
        this.sendNotificationUseCase = sendNotificationUseCase;
    }

    protected void handleRecord(ConsumerRecord<String, String> record, Acknowledgment ack) {
        String eventId;
        try {
            eventId = extractEventId(record);
        } catch (Exception e) {
            log.error("Failed to extract eventId from record: topic={}, partition={}, offset={}, error={}",
                record.topic(), record.partition(), record.offset(), e.getMessage());
            ack.acknowledge();
            return;
        }

        processedEventPort.existsByEventId(eventId)
            .flatMap(exists -> {
                if (exists) {
                    log.info("Duplicate event skipped: eventId={}", eventId);
                    return Mono.empty();
                }
                return processEvent(record)
                    .flatMap(sendNotificationUseCase::execute)
                    .then(processedEventPort.markAsProcessed(eventId, consumerGroup()));
            })
            .doOnSuccess(v -> ack.acknowledge())
            .doOnError(e -> {
                log.error("Failed to process event: eventId={}, error={}", eventId, e.getMessage());
                ack.acknowledge();
            })
            .subscribe();
    }

    protected abstract String extractEventId(ConsumerRecord<String, String> record);

    protected abstract Mono<NotificationRequest> processEvent(ConsumerRecord<String, String> record);

    protected abstract EventType resolveEventType(String eventType);

    protected abstract String consumerGroup();
}
