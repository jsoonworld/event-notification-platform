package com.jsoonworld.notification.infrastructure.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import reactor.test.StepVerifier;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class JwtAuthenticationManagerTest {

    private static final String SECRET = "test-secret-key-that-is-at-least-32-characters-long-for-hmac";
    private JwtAuthenticationManager authenticationManager;
    private SecretKey secretKey;

    @BeforeEach
    void setUp() {
        authenticationManager = new JwtAuthenticationManager(SECRET);
        secretKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void authenticate_withValidToken_returnsAuthentication() {
        String token = Jwts.builder()
                .subject("123")
                .claim("role", "USER")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 86400000))
                .signWith(secretKey)
                .compact();

        Authentication authRequest = new UsernamePasswordAuthenticationToken(token, token);

        StepVerifier.create(authenticationManager.authenticate(authRequest))
                .assertNext(auth -> {
                    assertThat(auth.getPrincipal()).isEqualTo("123");
                    assertThat(auth.getAuthorities()).hasSize(1);
                    assertThat(auth.getAuthorities().iterator().next().getAuthority()).isEqualTo("ROLE_USER");
                })
                .verifyComplete();
    }

    @Test
    void authenticate_withValidTokenNoRole_returnsAuthenticationWithNoAuthorities() {
        String token = Jwts.builder()
                .subject("456")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 86400000))
                .signWith(secretKey)
                .compact();

        Authentication authRequest = new UsernamePasswordAuthenticationToken(token, token);

        StepVerifier.create(authenticationManager.authenticate(authRequest))
                .assertNext(auth -> {
                    assertThat(auth.getPrincipal()).isEqualTo("456");
                    assertThat(auth.getAuthorities()).isEmpty();
                })
                .verifyComplete();
    }

    @Test
    void authenticate_withExpiredToken_throwsError() {
        String token = Jwts.builder()
                .subject("123")
                .issuedAt(new Date(System.currentTimeMillis() - 200000))
                .expiration(new Date(System.currentTimeMillis() - 100000))
                .signWith(secretKey)
                .compact();

        Authentication authRequest = new UsernamePasswordAuthenticationToken(token, token);

        StepVerifier.create(authenticationManager.authenticate(authRequest))
                .expectError()
                .verify();
    }

    @Test
    void authenticate_withInvalidSignature_throwsError() {
        SecretKey wrongKey = Keys.hmacShaKeyFor(
                "different-secret-key-that-is-also-at-least-32-characters".getBytes(StandardCharsets.UTF_8));

        String token = Jwts.builder()
                .subject("123")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 86400000))
                .signWith(wrongKey)
                .compact();

        Authentication authRequest = new UsernamePasswordAuthenticationToken(token, token);

        StepVerifier.create(authenticationManager.authenticate(authRequest))
                .expectError()
                .verify();
    }

    @Test
    void authenticate_withMalformedToken_throwsError() {
        Authentication authRequest = new UsernamePasswordAuthenticationToken("not-a-jwt", "not-a-jwt");

        StepVerifier.create(authenticationManager.authenticate(authRequest))
                .expectError()
                .verify();
    }
}
