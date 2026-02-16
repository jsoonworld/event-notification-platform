package com.jsoonworld.notification.infrastructure.config;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient.Builder webClientBuilder() {
        ConnectionProvider provider = ConnectionProvider.builder("custom")
            .maxConnections(50)
            .maxIdleTime(Duration.ofSeconds(30))
            .build();

        HttpClient httpClient = HttpClient.create(provider)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
            .responseTimeout(Duration.ofSeconds(10));

        return WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}
