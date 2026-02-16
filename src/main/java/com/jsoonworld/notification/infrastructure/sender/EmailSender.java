package com.jsoonworld.notification.infrastructure.sender;

import com.jsoonworld.notification.application.port.out.NotificationSender;
import com.jsoonworld.notification.domain.model.DeliveryResult;
import com.jsoonworld.notification.domain.model.NotificationChannel;
import com.jsoonworld.notification.domain.model.NotificationRequest;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
public class EmailSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(EmailSender.class);

    private final JavaMailSender mailSender;
    private final SpringTemplateEngine templateEngine;

    @Value("${notification.template.email.from:noreply@moalog.com}")
    private String fromEmail;

    public EmailSender(JavaMailSender mailSender, SpringTemplateEngine templateEngine) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
    }

    @Override
    public Mono<DeliveryResult> send(NotificationRequest request) {
        return Mono.fromCallable(() -> {
                MimeMessage message = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
                helper.setFrom(fromEmail);
                helper.setTo(request.recipient());
                helper.setSubject(request.subject());

                Context context = new Context();
                context.setVariables(request.templateVariables());
                String html = templateEngine.process(request.templateName(), context);
                helper.setText(html, true);

                mailSender.send(message);
                log.info("Email sent successfully: notificationId={}, recipient={}",
                    request.notificationId(), request.recipient());
                return DeliveryResult.success(NotificationChannel.EMAIL, request.notificationId());
            })
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(e -> {
                log.error("Failed to send email: notificationId={}, error={}",
                    request.notificationId(), e.getMessage());
                return Mono.just(DeliveryResult.failure(
                    NotificationChannel.EMAIL, request.notificationId(), e.getMessage()));
            });
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }
}
