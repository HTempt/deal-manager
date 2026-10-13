package com.dealmanager.service;

import com.dealmanager.domain.Customer;
import com.dealmanager.domain.FollowTask;
import com.dealmanager.domain.FollowRecord;
import com.dealmanager.mapper.CustomerMapper;
import com.dealmanager.mapper.FollowTaskMapper;
import com.dealmanager.mapper.FollowRecordMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.math.BigDecimal;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HomeServiceTest {
    @Test
    void overviewDiscoversUnscheduledCustomersAndSeparatesTodayFromOverdue() {
        CustomerMapper customers = mock(CustomerMapper.class);
        FollowTaskMapper tasks = mock(FollowTaskMapper.class);
        LocalDateTime today = LocalDate.now(AiQuota.ZONE).atStartOfDay();
        Customer unscheduled = customer(1L, null, 80);
        Customer overdue = customer(2L, today.minusDays(1), 20);
        Customer scheduled = customer(3L, today.plusHours(23), 50);
        when(customers.selectList(any())).thenReturn(List.of(unscheduled, overdue, scheduled));
        when(tasks.selectList(any())).thenReturn(List.of(task(2L, today.minusDays(1)),
                task(2L, today.minusDays(1)), task(3L, today.plusHours(23)), task(99L, today.minusDays(2))));
        HomeService.Overview overview = new HomeService(customers, tasks, mock(FollowRecordMapper.class)).overview(7L);
        assertEquals(3, overview.customerCount());
        assertEquals(1, overview.unscheduledCount());
        assertEquals(1, overview.overdueCount());
        assertEquals(1, overview.todayScheduledCount());
        assertEquals(1, overview.highIntentCount());
        assertTrue(overview.todayTasks().stream().noneMatch(t -> t.customerId().equals(99L)));
    }

    @Test
    void dormantCountIncludesBothSleepingGroupsButNotCustomersAtRiskOrRecentlyContacted() {
        CustomerMapper customers = mock(CustomerMapper.class); FollowTaskMapper tasks = mock(FollowTaskMapper.class);
        Customer risk = customer(1L, null, 80); risk.setLastFollowTime(LocalDateTime.now(AiQuota.ZONE).minusDays(14));
        Customer sleeping = customer(2L, null, 80); sleeping.setLastFollowTime(LocalDateTime.now(AiQuota.ZONE).minusDays(15));
        Customer longSleeping = customer(3L, null, 20); longSleeping.setLastFollowTime(LocalDateTime.now(AiQuota.ZONE).minusDays(31));
        Customer newUncontacted = customer(4L, null, 20); newUncontacted.setCreatedAt(LocalDateTime.now(AiQuota.ZONE).minusDays(18));
        Customer awake = customer(5L, null, 20); awake.setLastFollowTime(LocalDateTime.now(AiQuota.ZONE));
        when(customers.selectList(any())).thenReturn(List.of(risk, sleeping, longSleeping, newUncontacted, awake));
        when(tasks.selectList(any())).thenReturn(List.of());
        assertEquals(3, new HomeService(customers, tasks, mock(FollowRecordMapper.class)).overview(7L).dormantCount());
    }

    @Test
    void noCustomersHasNoPendingArrangement() {
        CustomerMapper customers = mock(CustomerMapper.class);
        FollowTaskMapper tasks = mock(FollowTaskMapper.class);
        when(customers.selectList(any())).thenReturn(List.of());
        when(tasks.selectList(any())).thenReturn(List.of());
        assertEquals(0, new HomeService(customers, tasks, mock(FollowRecordMapper.class)).overview(7L).unscheduledCount());
    }

    @Test
    void recommendationsAreUniqueAndIncludeInquiryWithoutATask() {
        CustomerMapper customers = mock(CustomerMapper.class);
        FollowTaskMapper tasks = mock(FollowTaskMapper.class);
        FollowRecordMapper records = mock(FollowRecordMapper.class);
        LocalDateTime today = LocalDate.now(AiQuota.ZONE).atStartOfDay();
        Customer overdue = customer(1L, today.minusDays(2), 80); overdue.setBudget(new BigDecimal("38000"));
        Customer inquiry = customer(2L, null, 60); inquiry.setBudget(new BigDecimal("20000"));
        FollowRecord record = new FollowRecord(); record.setCustomerId(2L); record.setCreatedAt(today.minusDays(1));
        record.setActiveInquiry(true); record.setCustomerFeedback("有兴趣");
        when(customers.selectList(any())).thenReturn(List.of(overdue, inquiry));
        when(tasks.selectList(any())).thenReturn(List.of(task(1L, today.minusDays(1)), task(1L, today.minusDays(2)),
                task(99L, today.minusDays(3))));
        when(records.selectLatestForActiveCustomers(eq(7L), any())).thenReturn(List.of(record));
        var overview = new HomeService(customers, tasks, records).overview(7L);
        assertEquals(2, overview.todayTasks().size());
        assertEquals("高意向 + ¥38,000 + 逾期2天", overview.todayTasks().get(0).reasonText());
        assertEquals(2L, overview.todayTasks().get(1).customerId());
        assertNull(overview.todayTasks().get(1).taskId());
        assertEquals("¥20,000 + 昨天主动咨询", overview.todayTasks().get(1).reasonText());
        assertEquals(1, overview.todayFollowCount()); // recommendations do not change task metrics
    }

    @Test
    void equalValueUsesStableOrderAndLimitAppliesAfterCustomerDeduplication() {
        CustomerMapper customers = mock(CustomerMapper.class);
        FollowTaskMapper tasks = mock(FollowTaskMapper.class);
        List<Customer> all = new ArrayList<>();
        for (long id = 12; id >= 1; id--) all.add(customer(id, null, 80));
        when(customers.selectList(any())).thenReturn(all);
        when(tasks.selectList(any())).thenReturn(List.of());
        var overview = new HomeService(customers, tasks, mock(FollowRecordMapper.class)).overview(7L);
        assertEquals(10, overview.todayTasks().size());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L),
                overview.todayTasks().stream().map(HomeService.PriorityCustomer::customerId).toList());
    }

    @Test
    void candidatePoolFeedsTheAiPlanWithTheSameRankingAndLimit() {
        CustomerMapper customers = mock(CustomerMapper.class);
        FollowTaskMapper tasks = mock(FollowTaskMapper.class);
        FollowRecordMapper records = mock(FollowRecordMapper.class);
        LocalDateTime today = LocalDate.now(AiQuota.ZONE).atStartOfDay();
        List<Customer> all = new ArrayList<>();
        for (long id = 1; id <= 14; id++) {
            Customer c = customer(id, null, 80); c.setBudget(new BigDecimal("20000"));
            c.setLastFollowTime(LocalDateTime.now(AiQuota.ZONE).minusDays(1));
            all.add(c);
        }
        // 明确安排在明天的客户尊重已有计划，不进入今天的候选池。
        Customer tomorrow = customer(99L, today.plusDays(1).plusHours(10), 90);
        all.add(tomorrow);
        when(customers.selectList(any())).thenReturn(all);
        when(tasks.selectList(any())).thenReturn(List.of());
        HomeService service = new HomeService(customers, tasks, records);
        var pool = service.candidatePool(7L, 12);
        assertEquals(12, pool.size());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), pool.stream().limit(5).map(c -> c.customer().getId()).toList());
        assertTrue(pool.stream().noneMatch(c -> c.customer().getId().equals(99L)));
        // AI 候选池与首页规则列表共用同一份排序，只按各自上限截断。
        assertEquals(pool.stream().limit(10).map(c -> c.customer().getId()).toList(),
                service.overview(7L).todayTasks().stream().map(HomeService.PriorityCustomer::customerId).toList());
    }

    private static Customer customer(Long id, LocalDateTime next, int score) {
        Customer c = new Customer(); c.setId(id); c.setUserId(7L); c.setStatus("ACTIVE");
        c.setCreatedAt(LocalDateTime.now(AiQuota.ZONE).minusDays(4)); c.setIntentionScore(score);
        c.setNextFollowTime(next); return c;
    }

    private static FollowTask task(Long customerId, LocalDateTime plan) {
        FollowTask t = new FollowTask(); t.setId(customerId); t.setUserId(7L);
        t.setCustomerId(customerId); t.setStatus("PENDING"); t.setPlanTime(plan); return t;
    }
}
