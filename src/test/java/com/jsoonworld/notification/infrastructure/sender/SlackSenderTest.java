package com.jsoonworld.notification.infrastructure.sender;

import com.jsoonworld.notification.domain.model.EventType;
import com.jsoonworld.notification.domain.model.NotificationChannel;
import com.jsoonworld.notification.domain.model.NotificationRequest;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.StringTemplateResolver;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SlackSenderTest {

    private MockWebServer mockWebServer;
    private SlackSender slackSender;

    @BeforeEach
    void setUp() throws Exception {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        SpringTemplateEngine templateEngine = new SpringTemplateEngine();
        StringTemplateResolver resolver = new StringTemplateResolver();
        resolver.setTemplateMode("TEXT");
        templateEngine.setTemplateResolver(resolver);

        WebClient.Builder webClientBuilder = WebClient.builder();
        slackSender = new SlackSender(webClientBuilder, templateEngine);

        // Set webhook URL via reflection
        Field webhookUrlField = SlackSender.class.getDeclaredField("webhookUrl");
        webhookUrlField.setAccessible(true);
        webhookUrlField.set(slackSender, mockWebServer.url("/webhook").toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    void send_success_returnsSuccessResult() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        NotificationRequest request = createRequest(EventType.PAYMENT_APPROVED);

        StepVerifier.create(slackSender.send(request))
            .assertNext(result -> {
                assertThat(result.isSuccess()).isTrue();
                assertThat(result.channel()).isEqualTo(NotificationChannel.SLACK);
                assertThat(result.notificationId()).isEqualTo("test-notif-id");
            })
            .verifyComplete();
    }

    @Test
    void send_http429_returnsFailureWithRateLimit() {
        mockWebServer.enqueue(new MockResponse()
            .setResponseCode(429)
            .addHeader("Retry-After", "30")
            .setBody("rate limited"));

        NotificationRequest request = createRequest(EventType.PAYMENT_FAILED);

        StepVerifier.create(slackSender.send(request))
            .assertNext(result -> {
                assertThat(result.isSuccess()).isFalse();
                assertThat(result.channel()).isEqualTo(NotificationChannel.SLACK);
                assertThat(result.errorMessage()).contains("SND_002");
                assertThat(result.errorMessage()).contains("429");
            })
            .verifyComplete();
    }

    @Test
    void send_http4xx_returnsFailure() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(400).setBody("bad request"));

        NotificationRequest request = createRequest(EventType.PAYMENT_APPROVED);

        StepVerifier.create(slackSender.send(request))
            .assertNext(result -> {
                assertThat(result.isSuccess()).isFalse();
                assertThat(result.channel()).isEqualTo(NotificationChannel.SLACK);
                assertThat(result.errorMessage()).contains("SND_002");
            })
            .verifyComplete();
    }

    @Test
    void send_http5xx_returnsFailure() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(500).setBody("server error"));

        NotificationRequest request = createRequest(EventType.PAYMENT_APPROVED);

        StepVerifier.create(slackSender.send(request))
            .assertNext(result -> {
                assertThat(result.isSuccess()).isFalse();
                assertThat(result.channel()).isEqualTo(NotificationChannel.SLACK);
                assertThat(result.errorMessage()).contains("SND_002");
                assertThat(result.errorMessage()).contains("Server error");
            })
            .verifyComplete();
    }

    @Test
    void channel_returnsSLACK() {
        assertThat(slackSender.channel()).isEqualTo(NotificationChannel.SLACK);
    }

    private NotificationRequest createRequest(EventType eventType) {
        return new NotificationRequest(
            "test-notif-id",
            "test-event-id",
            eventType,
            1L,
            "user@test.com",
            "[moalog] Test",
            "slack/payment-completed",
            Map.of(
                "paymentId", "PAY-001",
                "orderId", "ORD-001",
                "amount", "50000",
                "currency", "KRW",
                "failureReason", "Insufficient funds"
            )
        );
    }
}
