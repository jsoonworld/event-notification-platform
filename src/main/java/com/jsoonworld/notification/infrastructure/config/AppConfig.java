package com.jsoonworld.notification.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jsoonworld.notification.application.port.out.DeliveryStatsPort;
import com.jsoonworld.notification.application.port.out.NotificationLogPort;
import com.jsoonworld.notification.domain.service.DeliveryTracker;
import com.jsoonworld.notification.domain.service.NotificationRouter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    @Bean
    public NotificationRouter notificationRouter() {
        return new NotificationRouter();
    }

    @Bean
    public DeliveryTracker deliveryTracker(NotificationLogPort notificationLogPort,
                                           DeliveryStatsPort deliveryStatsPort) {
        return new DeliveryTracker(notificationLogPort, deliveryStatsPort);
    }
}
