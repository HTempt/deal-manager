package com.dealmanager.service;

import com.dealmanager.common.ApiException;
import com.dealmanager.domain.Customer;
import com.dealmanager.domain.FollowRecord;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class IntentionScoreTest {
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 9, 12, 0);

    @Test
    void twentyRepeatedFollowupsDoNotAccumulatePoints() {
        Customer c = customer();
        FollowRecord reply = follow(now, "有兴趣", false, false);
        int once = IntentionScore.calculate(c, List.of(reply), now);
        c.setFollowCount(20); c.setIntentionScore(100);
        assertEquals(once, IntentionScore.calculate(c, IntStream.range(0, 20).mapToObj(i -> reply).toList(), now));
        assertTrue(once < 70);
    }

    @Test
    void unansweredAttemptsCannotSupplyRecencyInquiryOrFrequencyPoints() {
        Customer c = customer();
        FollowRecord attempt = follow(now, "没有回应", true, true);
        assertEquals(IntentionScore.calculate(c, List.of(), now), IntentionScore.calculate(c, List.of(attempt), now));
        int positive = IntentionScore.calculate(c, List.of(follow(now.minusDays(1), "准备成交", true, true)), now);
        int negative = IntentionScore.calculate(c, List.of(attempt, follow(now.minusDays(1), "准备成交", true, true)), now);
        assertTrue(negative < positive);
    }

    @Test
    void staleSignalsExpireAndTimeWithoutRepliesLowersTheScore() {
        Customer c = customer();
        FollowRecord response = follow(now, "有兴趣", true, true);
        assertTrue(IntentionScore.calculate(c, List.of(response), now.plusDays(15)) < IntentionScore.calculate(c, List.of(response), now));
        assertEquals(IntentionScore.calculate(c, List.of(), now), IntentionScore.calculate(c, List.of(response), now.plusDays(31)));
    }

    @Test
    void frequencyIsCappedAndLargeBudgetsDoNotDominateIntent() {
        Customer c = customer(); c.setStage("谈判中");
        List<FollowRecord> history = IntStream.range(0, 14).mapToObj(i -> follow(now.minusDays(i), "准备成交", true, true)).toList();
        assertEquals(100, IntentionScore.calculate(c, history, now));
        c.setBudget(new BigDecimal("99999999"));
        assertEquals(100, IntentionScore.calculate(c, history, now));
        c.setStatus("LOST"); assertEquals(0, IntentionScore.calculate(c, history, now));
        c.setStatus("DEAL"); assertEquals(100, IntentionScore.calculate(c, history, now));
    }

    @Test
    void modesAndManualBoundsAreValidated() {
        assertEquals("MANUAL", IntentionScore.mode(null, "MANUAL"));
        assertEquals("AUTO", IntentionScore.mode("AUTO", "MANUAL"));
        assertEquals(0, IntentionScore.manual(0)); assertEquals(100, IntentionScore.manual(100));
        assertThrows(ApiException.class, () -> IntentionScore.manual(-1));
        assertThrows(ApiException.class, () -> IntentionScore.manual(101));
        assertThrows(ApiException.class, () -> IntentionScore.manual(null));
        assertThrows(ApiException.class, () -> IntentionScore.mode("AI", null));
    }

    private Customer customer() {
        Customer c = new Customer(); c.setStage("报价中"); c.setStatus("ACTIVE"); c.setBudget(new BigDecimal("150000"));
        return c;
    }

    private FollowRecord follow(LocalDateTime at, String feedback, boolean inquiry, boolean price) {
        FollowRecord r = new FollowRecord(); r.setCreatedAt(at); r.setCustomerFeedback(feedback);
        r.setActiveInquiry(inquiry); r.setPriceInquiry(price); return r;
    }
}
