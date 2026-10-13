package com.dealmanager.ai;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CustomerBriefTest {
    @Test
    void parsesAndTrimsStructuredSummary() {
        CustomerBrief result = CustomerBrief.parse("{\"overview\":\" 客户预算15万元 \\n\",\"suggestion\":\"2天后确认需求\"}");
        assertEquals("客户预算15万元", result.overview());
        assertEquals("2天后确认需求", result.suggestion());
    }

    @Test
    void rejectsIncompleteEmptyNonTextDuplicateAndTrailingResponses() {
        for (String response : List.of("null", "[]", "话术", "{}", "{\"overview\":\"客户\"}",
                "{\"overview\":5,\"suggestion\":\"建议\"}", "{\"overview\":\"客户\",\"suggestion\":\" \"}",
                "{\"overview\":\"客户\",\"overview\":\"覆盖\",\"suggestion\":\"建议\"}",
                "{\"overview\":\"客户\",\"suggestion\":\"建议\"} {}",
                "{\"overview\":\"" + "字".repeat(1001) + "\",\"suggestion\":\"建议\"}",
                "{\"overview\":\"客户\",\"suggestion\":\"" + "字".repeat(501) + "\"}")) {
            assertThrows(IllegalArgumentException.class, () -> CustomerBrief.parse(response));
        }
    }
}
