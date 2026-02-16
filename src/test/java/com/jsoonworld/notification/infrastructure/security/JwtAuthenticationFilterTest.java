package com.jsoonworld.notification.infrastructure.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpCookie;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class JwtAuthenticationFilterTest {

    private static final String SECRET = "test-secret-key-that-is-at-least-32-characters-long-for-hmac";
    private JwtAuthenticationFilter filter;
    private SecretKey secretKey;

    @BeforeEach
    void setUp() {
        JwtAuthenticationManager manager = new JwtAuthenticationManager(SECRET);
        filter = new JwtAuthenticationFilter(manager);
        secretKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    private String createValidToken(String userId) {
        return Jwts.builder()
                .subject(userId)
                .claim("role", "USER")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 86400000))
                .signWith(secretKey)
                .compact();
    }

    @Test
    void filter_withBearerHeader_extractsTokenAndSetsAuthentication() {
        String token = createValidToken("42");

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/settings")
                .header("Authorization", "Bearer " + token)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        WebFilterChain chain = ex -> Mono.deferContextual(ctx ->
                ReactiveSecurityContextHolder.getContext()
                        .map(SecurityContext::getAuthentication)
                        .doOnNext(auth -> assertThat(auth.getPrincipal()).isEqualTo("42"))
                        .then()
        );

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
    }

    @Test
    void filter_withCookieFallback_extractsToken() {
        String token = createValidToken("99");

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/settings")
                .cookie(new HttpCookie("access_token", token))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        WebFilterChain chain = ex -> Mono.deferContextual(ctx ->
                ReactiveSecurityContextHolder.getContext()
                        .map(SecurityContext::getAuthentication)
                        .doOnNext(auth -> assertThat(auth.getPrincipal()).isEqualTo("99"))
                        .then()
        );

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
    }

    @Test
    void filter_withNoToken_proceedsWithoutAuthentication() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/health").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        WebFilterChain chain = ex -> Mono.empty();

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
    }

    @Test
    void filter_headerTakesPriorityOverCookie() {
        String headerToken = createValidToken("100");
        String cookieToken = createValidToken("200");

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/settings")
                .header("Authorization", "Bearer " + headerToken)
                .cookie(new HttpCookie("access_token", cookieToken))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        WebFilterChain chain = ex -> Mono.deferContextual(ctx ->
                ReactiveSecurityContextHolder.getContext()
                        .map(SecurityContext::getAuthentication)
                        .doOnNext(auth -> assertThat(auth.getPrincipal()).isEqualTo("100"))
                        .then()
        );

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
    }
}
