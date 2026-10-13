package com.dealmanager.ai;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DailyPlanTest {
    private static final Set<Long> CANDIDATES = Set.of(8L, 9L, 10L);
    private static final String VALID = "{\"overview\":\"今天先联系报价后未回复的客户\",\"items\":["
            + "{\"customerId\":8,\"priority\":\"S\",\"advice\":\"报价 ¥38,000，3 天未回复，建议今天下午电话联系\"},"
            + "{\"customerId\":9,\"priority\":\"a\",\"advice\":\"昨天主动咨询，建议今天上午微信确认需求\"}]}";

    @Test
    void parsesExpectedItemsAndNormalizesPriorityCase() {
        DailyPlan plan = DailyPlan.parse(VALID, CANDIDATES, 2);
        assertEquals("今天先联系报价后未回复的客户", plan.overview());
        assertEquals(2, plan.items().size());
        assertEquals(8L, plan.items().get(0).customerId());
        assertEquals("S", plan.items().get(0).priority());
        assertEquals("A", plan.items().get(1).priority());
    }

    @Test
    void rejectsUnknownDuplicateOrWrongCountItems() {
        for (String response : List.of(
                // 候选池以外的客户不能被推荐
                "{\"overview\":\"建议\",\"items\":[{\"customerId\":99,\"priority\":\"S\",\"advice\":\"联系他\"},{\"customerId\":9,\"priority\":\"A\",\"advice\":\"联系他\"}]}",
                // 同一客户重复出现
                "{\"overview\":\"建议\",\"items\":[{\"customerId\":8,\"priority\":\"S\",\"advice\":\"联系他\"},{\"customerId\":8,\"priority\":\"A\",\"advice\":\"联系他\"}]}",
                // 数量与候选数量不符
                "{\"overview\":\"建议\",\"items\":[{\"customerId\":8,\"priority\":\"S\",\"advice\":\"联系他\"}]}",
                // 优先级不在 S/A/B 内
                "{\"overview\":\"建议\",\"items\":[{\"customerId\":8,\"priority\":\"C\",\"advice\":\"联系他\"},{\"customerId\":9,\"priority\":\"A\",\"advice\":\"联系他\"}]}",
                // 建议为空、超长或非文本
                "{\"overview\":\"建议\",\"items\":[{\"customerId\":8,\"priority\":\"S\",\"advice\":\" \"},{\"customerId\":9,\"priority\":\"A\",\"advice\":\"联系他\"}]}",
                "{\"overview\":\"建议\",\"items\":[{\"customerId\":8,\"priority\":\"S\",\"advice\":\"" + "字".repeat(201) + "\"},{\"customerId\":9,\"priority\":\"A\",\"advice\":\"联系他\"}]}",
                "{\"overview\":\"建议\",\"items\":[{\"customerId\":8,\"priority\":\"S\",\"advice\":5},{\"customerId\":9,\"priority\":\"A\",\"advice\":\"联系他\"}]}",
                // customerId 不是整数
                "{\"overview\":\"建议\",\"items\":[{\"customerId\":\"8\",\"priority\":\"S\",\"advice\":\"联系他\"},{\"customerId\":9,\"priority\":\"A\",\"advice\":\"联系他\"}]}",
                // 缺少整体判断或整体判断为空
                "{\"items\":[{\"customerId\":8,\"priority\":\"S\",\"advice\":\"联系他\"},{\"customerId\":9,\"priority\":\"A\",\"advice\":\"联系他\"}]}",
                "{\"overview\":\"  \",\"items\":[{\"customerId\":8,\"priority\":\"S\",\"advice\":\"联系他\"},{\"customerId\":9,\"priority\":\"A\",\"advice\":\"联系他\"}]}",
                // 非对象、数组、空响应或多余内容
                "null", "[]", "话术", "{}", VALID + " {}")) {
            assertThrows(IllegalArgumentException.class, () -> DailyPlan.parse(response, CANDIDATES, 2));
        }
    }

    @Test
    void shapeValidationOnlyChecksStructureSoThePoolCanRetryMalformedOutput() {
        DailyPlan shape = DailyPlan.parseShape(VALID);
        assertEquals(2, shape.items().size());
        // 条数与候选范围由业务层按当前候选池判断，形状校验放行结构完整的响应。
        assertEquals(1, DailyPlan.parseShape(
                "{\"overview\":\"建议\",\"items\":[{\"customerId\":99,\"priority\":\"S\",\"advice\":\"联系他\"}]}").items().size());
        for (String response : List.of("null", "[]", "话术", "{}", "{\"overview\":\"建议\"}",
                "{\"overview\":\"建议\",\"items\":[]}", "{\"items\":[{\"customerId\":8,\"priority\":\"S\",\"advice\":\"联系他\"}]}")) {
            assertThrows(IllegalArgumentException.class, () -> DailyPlan.parseShape(response));
        }
    }

    @Test
    void storedItemsAreRevalidatedSoStaleRowsCannotBeTrusted() {
        List<DailyPlan.Item> items = DailyPlan.parseItems(
                "[{\"customerId\":8,\"priority\":\"S\",\"advice\":\"今天下午电话联系\"}]");
        assertEquals(1, items.size());
        assertEquals("今天下午电话联系", items.get(0).advice());

        for (String stored : List.of("", "  ", "[]", "null", "{}", "不建议",
                "[{\"customerId\":8,\"priority\":\"X\",\"advice\":\"联系\"}]",
                "[{\"customerId\":8,\"priority\":\"S\",\"advice\":\" \"}]",
                "[{\"customerId\":8,\"priority\":\"S\",\"advice\":\"联系\"},{\"customerId\":8,\"priority\":\"A\",\"advice\":\"联系\"}]")) {
            assertThrows(IllegalArgumentException.class, () -> DailyPlan.parseItems(stored));
        }
    }
}