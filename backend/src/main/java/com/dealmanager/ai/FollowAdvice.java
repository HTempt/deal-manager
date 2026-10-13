package com.dealmanager.ai;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public record FollowAdvice(String assessment, String suggestedAction, String content,
                           Integer followUpDays, String followUpReason) {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    public static FollowAdvice parse(String response) {
        try {
            JsonNode node = JSON.readTree(response);
            if (node == null || !node.isObject() || !node.has("followUpDays")) throw new IllegalArgumentException();
            JsonNode interval = node.get("followUpDays");
            Integer days = null;
            if (!interval.isNull()) {
                if (!interval.isIntegralNumber() || !interval.canConvertToInt()) throw new IllegalArgumentException();
                days = interval.intValue();
                if (days < 1 || days > 30) throw new IllegalArgumentException();
            }
            return new FollowAdvice(text(node, "assessment", 500), text(node, "suggestedAction", 500),
                    text(node, "content", 2000), days, text(node, "followUpReason", 300));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid AI follow-up advice");
        }
    }

    private static String text(JsonNode node, String field, int max) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) throw new IllegalArgumentException();
        String text = value.textValue().strip();
        if (text.isEmpty() || text.codePointCount(0, text.length()) > max) throw new IllegalArgumentException();
        return text;
    }
}
