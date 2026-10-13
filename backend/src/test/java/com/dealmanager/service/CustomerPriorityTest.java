package com.dealmanager.service;

import com.dealmanager.domain.Customer;
import com.dealmanager.domain.FollowRecord;
import com.dealmanager.domain.FollowTask;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class CustomerPriorityTest {
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 10, 10, 0);

    @Test
    void highIntentAmountAndOverdueProduceAnExplanation() {
        Customer c = customer(80, "38000");
        var result = CustomerPriority.assess(c, task(now.minusDays(2)), null, now);
        assertNotNull(result);
        assertEquals("高意向 + ¥38,000 + 逾期2天", result.reasonText());
    }

    @Test
    void recentInquiryCanOutrankHigherIntentEvenWithoutATask() {
        Customer inquiry = customer(60, "20000");
        Customer passive = customer(90, "20000");
        var active = CustomerPriority.assess(inquiry, null, record(1, "有兴趣", true), now);
        var regular = CustomerPriority.assess(passive, task(now), null, now);
        assertNotNull(active); assertNotNull(regular);
        assertTrue(active.score() > regular.score());
        assertEquals("¥20,000 + 昨天主动咨询", active.reasonText());
        assertNull(CustomerPriority.assess(inquiry, null, record(3, "有兴趣", true), now));
        assertNull(CustomerPriority.assess(inquiry, null, record(1, "没有回应", true), now));
    }

    @Test
    void quoteExplanationRequiresActualNoReplyAndDoesNotInventASendDate() {
        Customer c = customer(30, null); c.setStage("报价中");
        assertNull(CustomerPriority.assess(c, null, null, now));
        assertNull(CustomerPriority.assess(c, null, record(3, "考虑中", false), now));
        assertNull(CustomerPriority.assess(c, null, record(2, "没有回应", false), now));
        assertEquals("报价中，3天前记录未回复",
                CustomerPriority.assess(c, null, record(3, "没有回应", false), now).reasonText());
        c.setStage("服务报价");
        assertNotNull(CustomerPriority.assess(c, null, record(3, "没有回应", false), now));
    }

    @Test
    void dormantHighValueOpportunityDistinguishesNeverContactedCustomers() {
        Customer c = customer(30, "50000"); c.setLastFollowTime(now.minusDays(15));
        assertEquals("¥50,000 + 15天未联系", CustomerPriority.assess(c, null, null, now).reasonText());
        c.setLastFollowTime(null); c.setCreatedAt(now.minusDays(15));
        var neverContacted = CustomerPriority.assess(c, null, null, now);
        assertEquals("¥50,000 + 录入15天尚未联系", neverContacted.reasonText());
        assertFalse(neverContacted.hasContactHistory());
        c.setCreatedAt(now.minusDays(14));
        assertNull(CustomerPriority.assess(c, null, null, now));
        c.setCreatedAt(now.minusDays(15)); c.setBudget(BigDecimal.ONE);
        assertNull(CustomerPriority.assess(c, null, null, now));
    }

    @Test
    void honoursFuturePlansAndExcludesClosedCustomersButIncludesLaterToday() {
        Customer c = customer(80, "38000"); c.setNextFollowTime(now.plusDays(1));
        assertNull(CustomerPriority.assess(c, null, record(1, "有兴趣", true), now));
        assertNotNull(CustomerPriority.assess(c, task(now.minusDays(2)), null, now));
        c.setNextFollowTime(now.plusHours(10));
        assertEquals("高意向 + ¥38,000 + 今天有跟进安排",
                CustomerPriority.assess(c, task(now.plusHours(10)), null, now).reasonText());
        for (String status : new String[]{"LOST", "DEAL", "DELETED"}) {
            c.setStatus(status);
            assertNull(CustomerPriority.assess(c, task(now), null, now));
        }
    }

    @Test
    void hugeBudgetsCannotDrownOutUrgencyAndMissingFieldsAreSafe() {
        Customer urgent = customer(80, "38000");
        Customer huge = customer(80, "999999999999");
        assertTrue(CustomerPriority.assess(urgent, task(now.minusDays(2)), null, now).score()
                > CustomerPriority.assess(huge, task(now), null, now).score());
        Customer empty = new Customer(); empty.setStatus("ACTIVE");
        assertEquals("今天有跟进安排", CustomerPriority.assess(empty, task(now), null, now).reasonText());
        assertNull(CustomerPriority.assess(empty, null, null, now));
    }

    @Test
    void overdueUsesBeijingCalendarDaysRatherThanCompletedTwentyFourHourPeriods() {
        var result = CustomerPriority.assess(customer(30, null), task(now.toLocalDate().atStartOfDay().minusMinutes(1)), null, now);
        assertEquals("逾期1天", result.reasonText());
    }

    private Customer customer(int intent, String budget) {
        Customer c = new Customer(); c.setStatus("ACTIVE"); c.setIntentionScore(intent);
        c.setBudget(budget == null ? null : new BigDecimal(budget)); c.setCreatedAt(now.minusDays(4));
        return c;
    }

    private FollowRecord record(int days, String feedback, boolean inquiry) {
        FollowRecord r = new FollowRecord(); r.setCreatedAt(now.minusDays(days));
        r.setCustomerFeedback(feedback); r.setActiveInquiry(inquiry); return r;
    }

    private FollowTask task(LocalDateTime plan) {
        FollowTask t = new FollowTask(); t.setPlanTime(plan); return t;
    }
}
