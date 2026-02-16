package com.jsoonworld.notification.interfaces.rest;

import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    private final DatabaseClient databaseClient;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final JavaMailSender mailSender;

    public HealthController(DatabaseClient databaseClient,
                            ReactiveRedisTemplate<String, String> redisTemplate,
                            JavaMailSender mailSender) {
        this.databaseClient = databaseClient;
        this.redisTemplate = redisTemplate;
        this.mailSender = mailSender;
    }

    @GetMapping
    public Mono<Map<String, Object>> health() {
        Mono<String> postgresStatus = checkPostgres();
        Mono<String> redisStatus = checkRedis();
        Mono<String> smtpStatus = checkSmtp();

        return Mono.zip(postgresStatus, redisStatus, smtpStatus)
            .map(tuple -> {
                String postgres = tuple.getT1();
                String redis = tuple.getT2();
                String smtp = tuple.getT3();

                Map<String, String> components = new LinkedHashMap<>();
                components.put("postgres", postgres);
                components.put("redis", redis);
                components.put("smtp", smtp);

                boolean allUp = components.values().stream().allMatch("UP"::equals);
                String overallStatus = allUp ? "UP" : "DEGRADED";

                Map<String, Object> data = new LinkedHashMap<>();
                data.put("status", overallStatus);
                data.put("components", components);

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("success", true);
                response.put("data", data);
                return response;
            });
    }

    private Mono<String> checkPostgres() {
        return databaseClient.sql("SELECT 1")
            .fetch()
            .first()
            .map(result -> "UP")
            .onErrorReturn("DOWN");
    }

    private Mono<String> checkRedis() {
        return redisTemplate.getConnectionFactory()
            .getReactiveConnection()
            .ping()
            .map(pong -> "UP")
            .onErrorReturn("DOWN");
    }

    private Mono<String> checkSmtp() {
        return Mono.fromCallable(() -> {
                mailSender.createMimeMessage();
                return "UP";
            })
            .onErrorReturn("DOWN");
    }
}
