package com.dealmanager.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FollowAdviceTest {
    private static final String VALID = "{\"assessment\":\"可能对预算有顾虑\",\"suggestedAction\":\"询问总价还是项目范围\",\"content\":\"您主要顾虑的是哪一部分？\",\"followUpDays\":2,\"followUpReason\":\"留出确认时间\"}";

    @Test
    void readsActualAssessmentActionAndInterval() {
        FollowAdvice advice = FollowAdvice.parse(VALID);
        assertEquals("可能对预算有顾虑", advice.assessment());
        assertEquals("询问总价还是项目范围", advice.suggestedAction());
        assertEquals(2, advice.followUpDays());
        assertEquals("留出确认时间", advice.followUpReason());
    }

    @Test
    void rejectsMalformedTypesOutOfRangeEmptyFieldsDuplicatesAndExtraJson() {
        for (String invalid : new String[]{"not JSON", "[]", "{}", VALID + "{}",
                VALID.replace("\"assessment\":", "\"assessment\":\"duplicate\",\"assessment\":"),
                VALID.replace("可能对预算有顾虑", " "),
                VALID.replace("\"followUpDays\":2", "\"followUpDays\":\"2\""),
                VALID.replace("\"followUpDays\":2", "\"followUpDays\":2.5"),
                VALID.replace("\"followUpDays\":2", "\"followUpDays\":0"),
                VALID.replace("\"followUpDays\":2", "\"followUpDays\":31"),
                VALID.replace("可能对预算有顾虑", "a".repeat(501))}) {
            assertThrows(IllegalArgumentException.class, () -> FollowAdvice.parse(invalid));
        }
    }

    @Test
    void allowsNoFollowUpForTerminalCustomer() {
        assertNull(FollowAdvice.parse(VALID.replace("\"followUpDays\":2", "\"followUpDays\":null")).followUpDays());
    }
}
