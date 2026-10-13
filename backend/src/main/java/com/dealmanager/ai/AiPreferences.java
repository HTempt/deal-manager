package com.dealmanager.ai;

import com.dealmanager.domain.UserAiSetting;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public record AiPreferences(String replyStyle, String preferredAddress, String replyLength,
                            String businessIntro, String businessAdvantage, String commonExpression) {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static AiPreferences from(UserAiSetting setting) {
        if (setting == null) setting = new UserAiSetting();
        return new AiPreferences(value(setting.getReplyStyle(), "自然亲切"),
                value(setting.getPreferredAddress(), "自动判断"), value(setting.getReplyLength(), "适中"),
                value(setting.getBusinessIntro(), ""), value(setting.getBusinessAdvantage(), ""),
                value(setting.getCommonExpression(), ""));
    }

    public String toJson() {
        try { return JSON.writeValueAsString(this); }
        catch (Exception e) { throw new IllegalStateException("AI preferences serialization failed"); }
    }

    public String fingerprint() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(toJson().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("AI preferences fingerprint failed"); }
    }

    private static String value(String text, String fallback) {
        return StringUtils.hasText(text) ? text.strip() : fallback;
    }
}
