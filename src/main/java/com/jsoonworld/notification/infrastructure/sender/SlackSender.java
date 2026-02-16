package com.jsoonworld.notification.infrastructure.sender;

import com.jsoonworld.notification.application.port.out.NotificationSender;
import com.jsoonworld.notification.domain.model.DeliveryResult;
import com.jsoonworld.notification.domain.model.NotificationChannel;
import com.jsoonworld.notification.domain.model.NotificationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Component
public class SlackSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(SlackSender.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final WebClient webClient;
    private final SpringTemplateEngine templateEngine;

    @Value("${notification.slack.webhook-url}")
    private String webhookUrl;

    public SlackSender(WebClient.Builder webClientBuilder, SpringTemplateEngine templateEngine) {
        this.webClient = webClientBuilder.build();
        this.templateEngine = templateEngine;
    }

    @Override
    public Mono<DeliveryResult> send(NotificationRequest request) {
        String templateName = resolveSlackTemplate(request);
        Context context = new Context();
        context.setVariables(request.templateVariables());

        String payload;
        try {
            payload = templateEngine.process(templateName, context);
        } catch (Exception e) {
            log.error("Failed to render Slack template: notificationId={}, template={}, error={}",
                request.notificationId(), templateName, e.getMessage());
            return Mono.just(DeliveryResult.failure(
                NotificationChannel.SLACK, request.notificationId(),
                "SND_002: Template rendering failed - " + e.getMessage()));
        }

        return webClient.post()
            .uri(webhookUrl)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(payload)
            .retrieve()
            .toBodilessEntity()
            .timeout(TIMEOUT)
            .map(response -> {
                log.info("Slack notification sent successfully: notificationId={}", request.notificationId());
                return DeliveryResult.success(NotificationChannel.SLACK, request.notificationId());
            })
            .onErrorResume(WebClientResponseException.TooManyRequests.class, e -> {
                String retryAfter = e.getHeaders().getFirst("Retry-After");
                log.warn("Slack rate limited (429): notificationId={}, retryAfter={}",
                    request.notificationId(), retryAfter);
                return Mono.just(DeliveryResult.failure(
                    NotificationChannel.SLACK, request.notificationId(),
                    "SND_002: Rate limited (429), Retry-After=" + retryAfter));
            })
            .onErrorResume(WebClientResponseException.class, e -> {
                if (e.getStatusCode().is4xxClientError()) {
                    log.error("Slack client error (4xx): notificationId={}, status={}, error={}",
                        request.notificationId(), e.getStatusCode(), e.getMessage());
                    return Mono.just(DeliveryResult.failure(
                        NotificationChannel.SLACK, request.notificationId(),
                        "SND_002: Client error " + e.getStatusCode()));
                }
                log.error("Slack server error (5xx): notificationId={}, status={}, error={}",
                    request.notificationId(), e.getStatusCode(), e.getMessage());
                return Mono.just(DeliveryResult.failure(
                    NotificationChannel.SLACK, request.notificationId(),
                    "SND_002: Server error " + e.getStatusCode()));
            })
            .onErrorResume(WebClientRequestException.class, e -> {
                log.error("Slack network error: notificationId={}, error={}",
                    request.notificationId(), e.getMessage());
                return Mono.just(DeliveryResult.failure(
                    NotificationChannel.SLACK, request.notificationId(),
                    "SND_002: Network error - " + e.getMessage()));
            })
            .onErrorResume(Exception.class, e -> {
                log.error("Slack send failed: notificationId={}, error={}",
                    request.notificationId(), e.getMessage());
                return Mono.just(DeliveryResult.failure(
                    NotificationChannel.SLACK, request.notificationId(),
                    "SND_002: " + e.getMessage()));
            });
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.SLACK;
    }

    private String resolveSlackTemplate(NotificationRequest request) {
        return switch (request.eventType()) {
            case PAYMENT_APPROVED -> "slack/payment-completed";
            case PAYMENT_FAILED -> "slack/payment-failed";
            default -> "slack/payment-completed";
        };
    }
}
