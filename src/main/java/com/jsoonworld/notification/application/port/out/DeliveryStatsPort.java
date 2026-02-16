package com.jsoonworld.notification.application.port.out;

import reactor.core.publisher.Mono;

import java.time.LocalDate;

public interface DeliveryStatsPort {

    Mono<Void> incrementCount(LocalDate date, String channel, boolean success);
}
