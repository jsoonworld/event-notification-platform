package com.jsoonworld.notification.interfaces.rest;

import com.jsoonworld.notification.application.port.in.ManageSettingsUseCase;
import com.jsoonworld.notification.interfaces.rest.dto.request.NotificationSettingsRequest;
import com.jsoonworld.notification.interfaces.rest.dto.response.NotificationSettingsResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/settings")
public class NotificationSettingsController {

    private final ManageSettingsUseCase manageSettingsUseCase;

    public NotificationSettingsController(ManageSettingsUseCase manageSettingsUseCase) {
        this.manageSettingsUseCase = manageSettingsUseCase;
    }

    @GetMapping
    public Mono<NotificationSettingsResponse> getSettings() {
        return extractUserId()
                .flatMap(manageSettingsUseCase::getSettings)
                .map(NotificationSettingsResponse::from);
    }

    @PutMapping
    public Mono<NotificationSettingsResponse> updateSettings(@Valid @RequestBody NotificationSettingsRequest request) {
        return extractUserId()
                .flatMap(userId -> manageSettingsUseCase.updateSettings(
                        userId,
                        request.emailEnabled(),
                        request.slackEnabled(),
                        request.pushEnabled(),
                        request.inAppEnabled()))
                .map(NotificationSettingsResponse::from);
    }

    private Mono<Long> extractUserId() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(Authentication::getPrincipal)
                .map(principal -> Long.parseLong(principal.toString()));
    }
}
