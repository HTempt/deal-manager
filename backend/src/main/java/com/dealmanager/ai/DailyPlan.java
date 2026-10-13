package com.dealmanager.ai;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** AI 今日建议的严格结构：一段整体判断加固定数量的重点客户。 */
public record DailyPlan(String overview, List<Item> items) {
    public static final int MAX_ADVICE_CHARS = 200;
    private static final int MAX_OVERVIEW_CHARS = 500;
    private static final Set<String> PRIORITIES = Set.of("S", "A", "B");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    public record Item(long customerId, String priority, String advice) {}

    /** 模型返回必须恰好给出 expectedItems 位客户，且只能引用候选池里的 customerId。 */
    public static DailyPlan parse(String response, Set<Long> allowedCustomerIds, int expectedItems) {
        DailyPlan plan = read(response);
        if (plan.items().size() != expectedItems) throw new IllegalArgumentException("Invalid AI daily plan");
        for (Item item : plan.items()) {
            if (allowedCustomerIds == null || !allowedCustomerIds.contains(item.customerId())) {
                throw new IllegalArgumentException("Invalid AI daily plan");
            }
        }
        return plan;
    }

    /**
     * 只校验结构与字段格式，候选范围和条数由业务层用当前候选池判断。
     * 这样字段缺失或格式错误的响应会在模型池内切换到备用模型，而不是直接失败。
     */
    public static DailyPlan parseShape(String response) {
        return read(response);
    }

    /** 重新读取已保存的建议；结构无效时抛错，由调用方决定重新生成而不是展示不可信内容。 */
    public static List<Item> parseItems(String stored) {
        if (stored == null || stored.isBlank()) throw new IllegalArgumentException("Empty stored daily plan");
        try {
            JsonNode list = JSON.readTree(stored);
            if (list == null || !list.isArray() || list.isEmpty()) throw new IllegalArgumentException();
            return items(list);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid stored daily plan");
        }
    }

    private static DailyPlan read(String response) {
        try {
            JsonNode node = JSON.readTree(response);
            if (node == null || !node.isObject()) throw new IllegalArgumentException();
            String overview = text(node, "overview", MAX_OVERVIEW_CHARS);
            JsonNode list = node.get("items");
            if (list == null || !list.isArray() || list.isEmpty()) throw new IllegalArgumentException();
            return new DailyPlan(overview, items(list));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid AI daily plan");
        }
    }

    private static List<Item> items(JsonNode list) {
        List<Item> result = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (JsonNode element : list) {
            if (element == null || !element.isObject() || !seen.add(customerId(element))) throw new IllegalArgumentException();
            result.add(new Item(customerId(element), priority(element), text(element, "advice", MAX_ADVICE_CHARS)));
        }
        return List.copyOf(result);
    }

    private static long customerId(JsonNode element) {
        JsonNode value = element.get("customerId");
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) throw new IllegalArgumentException();
        return value.longValue();
    }

    private static String priority(JsonNode element) {
        String priority = text(element, "priority", 1).toUpperCase(Locale.ROOT);
        if (!PRIORITIES.contains(priority)) throw new IllegalArgumentException();
        return priority;
    }

    private static String text(JsonNode node, String field, int max) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) throw new IllegalArgumentException();
        String text = value.textValue().strip();
        if (text.isEmpty() || text.codePointCount(0, text.length()) > max) throw new IllegalArgumentException();
        return text;
    }
}