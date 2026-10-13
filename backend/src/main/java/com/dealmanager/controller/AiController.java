package com.dealmanager.controller;

import com.dealmanager.service.AiService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {
    private final AiService aiService;
    @PostMapping({"/generate-script", "/regenerate-script"})
    public AiService.AiResult generate(@AuthenticationPrincipal Long userId, @Valid @RequestBody AiRequest request) {
        return aiService.generate(userId, request.customerId(), request.scenario(), request.action(), request.previousContent());
    }
    public record AiRequest(@NotNull Long customerId, @NotBlank @jakarta.validation.constraints.Size(max = 100) String scenario,
                            String action, @jakarta.validation.constraints.Size(max = 4000) String previousContent) {}
}
