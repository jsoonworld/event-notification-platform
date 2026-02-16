package com.jsoonworld.notification.infrastructure.persistence.repository;

import com.jsoonworld.notification.infrastructure.persistence.entity.DeliveryStatsEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.time.LocalDate;

public interface DeliveryStatsR2dbcRepository extends ReactiveCrudRepository<DeliveryStatsEntity, Long> {

    Mono<DeliveryStatsEntity> findByDateAndChannel(LocalDate date, String channel);

    @Modifying
    @Query("INSERT INTO delivery_stats (date, channel, sent_count, failed_count, success_rate) " +
           "VALUES (:date, :channel, :sentCount, :failedCount, NULL) " +
           "ON CONFLICT (date, channel) DO UPDATE SET " +
           "sent_count = delivery_stats.sent_count + :sentCount, " +
           "failed_count = delivery_stats.failed_count + :failedCount, " +
           "success_rate = CASE WHEN (delivery_stats.sent_count + :sentCount + delivery_stats.failed_count + :failedCount) > 0 " +
           "THEN ROUND((delivery_stats.sent_count + :sentCount)::DECIMAL / " +
           "(delivery_stats.sent_count + :sentCount + delivery_stats.failed_count + :failedCount) * 100, 2) " +
           "ELSE 0 END")
    Mono<Void> upsertCount(LocalDate date, String channel, int sentCount, int failedCount);
}
