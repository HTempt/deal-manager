package com.dealmanager.controller;

import com.dealmanager.service.NotificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {
    private final NotificationService notificationService;

    @PostMapping("/subscribe")
    public void subscribe(@AuthenticationPrincipal Long userId, @Valid @RequestBody SubscribeRequest request) {
        notificationService.subscribe(userId, request.templateId(), request.subscribeToken(), request.expiresIn());
    }

    @DeleteMapping("/subscribe")
    public void unsubscribe(@AuthenticationPrincipal Long userId) { notificationService.unsubscribe(userId); }

    @GetMapping("/settings")
    public Settings settings(@AuthenticationPrincipal Long userId) {
        return new Settings(notificationService.enabled(userId));
    }

    @PutMapping("/settings")
    public Settings settings(@AuthenticationPrincipal Long userId, @Valid @RequestBody Settings request) {
        notificationService.setEnabled(userId, request.enabled());
        return new Settings(notificationService.enabled(userId));
    }

    public record SubscribeRequest(@NotBlank String templateId, @NotBlank String subscribeToken, Long expiresIn) {}
    public record Settings(@jakarta.validation.constraints.NotNull Boolean enabled) {}
}
