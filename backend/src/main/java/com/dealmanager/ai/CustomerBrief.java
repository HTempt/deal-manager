package com.dealmanager.ai;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public record CustomerBrief(String overview, String suggestion) {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    public static CustomerBrief parse(String response) {
        try {
            JsonNode node = JSON.readTree(response);
            if (node == null || !node.isObject()) throw new IllegalArgumentException();
            return new CustomerBrief(text(node, "overview", 1000), text(node, "suggestion", 500));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid customer summary");
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
