package com.dealmanager.controller;

import com.dealmanager.domain.IndustryConfig;
import com.dealmanager.domain.User;
import com.dealmanager.domain.UserAiSetting;
import com.dealmanager.service.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {
    private final SettingsService settingsService;

    @GetMapping("/industry")
    public Map<String, Object> industries(@AuthenticationPrincipal Long userId) {
        return Map.of("current", settingsService.user(userId).getIndustry() == null ? "" : settingsService.user(userId).getIndustry(),
                "options", settingsService.industries());
    }
    @PutMapping("/industry")
    public User updateIndustry(@AuthenticationPrincipal Long userId, @RequestBody IndustryRequest request) {
        return settingsService.setIndustry(userId, request.industry());
    }
    @GetMapping("/ai")
    public UserAiSetting ai(@AuthenticationPrincipal Long userId) { return settingsService.aiSetting(userId); }
    @PutMapping("/ai")
    public UserAiSetting updateAi(@AuthenticationPrincipal Long userId, @RequestBody AiRequest request) {
        return settingsService.updateAi(userId, request.toCommand());
    }
    public record IndustryRequest(String industry) {}
    public record AiRequest(String replyStyle, String replyLength, String preferredAddress,
                            String businessIntro, String businessAdvantage, String commonExpression) {
        SettingsService.AiSettingCommand toCommand() {
            return new SettingsService.AiSettingCommand(replyStyle, replyLength, preferredAddress,
                    businessIntro, businessAdvantage, commonExpression);
        }
    }
}
