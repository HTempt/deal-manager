package com.dealmanager.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dealmanager.domain.Customer;
import com.dealmanager.domain.FollowTask;
import com.dealmanager.domain.FollowRecord;
import com.dealmanager.common.ApiException;
import com.dealmanager.mapper.CustomerMapper;
import com.dealmanager.mapper.FollowRecordMapper;
import com.dealmanager.mapper.FollowTaskMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FollowServiceTest {
    private FollowTaskMapper tasks;
    private CustomerMapper customers;
    private FollowRecordMapper records;
    private FollowService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), FollowTask.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), Customer.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), FollowRecord.class);
        tasks = mock(FollowTaskMapper.class);
        customers = mock(CustomerMapper.class);
        records = mock(FollowRecordMapper.class);
        service = new FollowService(tasks, records, customers, new CustomerService(customers, records, tasks, mock(SettingsService.class)));
        when(tasks.selectList(any())).thenReturn(List.of());
        when(records.selectList(any())).thenReturn(List.of());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void upcomingStartsAtTomorrowInBeijingAndOnlyQueriesOwnersPendingTasks() {
        LocalDateTime start = LocalDate.now(AiQuota.ZONE).plusDays(1).atStartOfDay();
        assertTrue(service.upcoming(7L).isEmpty());
        ArgumentCaptor<LambdaQueryWrapper<FollowTask>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(tasks).selectList(captor.capture());
        var query = captor.getValue();
        String sql = query.getSqlSegment();
        assertTrue(sql.contains("user_id ="));
        assertTrue(sql.contains("status ="));
        assertTrue(sql.contains("plan_time >="));
        assertTrue(sql.contains("ORDER BY plan_time ASC,id ASC"));
        assertTrue(query.getParamNameValuePairs().containsValue(7L));
        assertTrue(query.getParamNameValuePairs().containsValue("PENDING"));
        assertTrue(query.getParamNameValuePairs().containsValue(start));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void todayAndOverdueHaveDisjointDayBoundaries() {
        LocalDateTime start = LocalDate.now(AiQuota.ZONE).atStartOfDay();
        service.today(7L);
        service.overdue(7L);
        ArgumentCaptor<LambdaQueryWrapper<FollowTask>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(tasks, times(2)).selectList(captor.capture());
        var today = captor.getAllValues().get(0);
        var overdue = captor.getAllValues().get(1);
        assertTrue(today.getSqlSegment().contains("plan_time >="));
        assertTrue(today.getSqlSegment().contains("plan_time <"));
        assertTrue(today.getParamNameValuePairs().containsValue(start));
        assertTrue(today.getParamNameValuePairs().containsValue(start.plusDays(1)));
        assertTrue(overdue.getSqlSegment().contains("plan_time <"));
        assertTrue(overdue.getParamNameValuePairs().containsValue(start));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void upcomingRequiresActiveCustomerOwnedByCurrentUser() {
        FollowTask task = task(8L);
        when(tasks.selectList(any())).thenReturn(List.of(task));
        when(customers.selectOne(any())).thenReturn(null);
        assertTrue(service.upcoming(7L).isEmpty());
        ArgumentCaptor<LambdaQueryWrapper<Customer>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(customers).selectOne(captor.capture());
        var query = captor.getValue();
        assertTrue(query.getSqlSegment().contains("user_id ="));
        assertTrue(query.getSqlSegment().contains("status ="));
        assertTrue(query.getParamNameValuePairs().containsValue(7L));
        assertTrue(query.getParamNameValuePairs().containsValue("ACTIVE"));
    }

    @Test
    void upcomingIncludesFarFutureTasksWithZeroOverdueDaysAndActionIds() {
        FollowTask task = task(8L);
        task.setPlanTime(LocalDate.now(AiQuota.ZONE).plusMonths(2).atStartOfDay());
        when(tasks.selectList(any())).thenReturn(List.of(task));
        Customer customer = new Customer(); customer.setId(8L); customer.setName("客户");
        when(customers.selectOne(any())).thenReturn(customer);
        var result = service.upcoming(7L).get(0);
        assertEquals(10L, result.taskId());
        assertEquals(8L, result.customerId());
        assertEquals(task.getPlanTime(), result.planTime());
        assertEquals(0, result.overdueDays());
    }

    @Test
    void savingFollowRecalculatesRatherThanApplyingClientDeltaAndPreservesStage() {
        Customer c = scoringCustomer();
        var first = service.create(7L, command("有兴趣", "有兴趣", "AUTO", null));
        assertEquals("报价中", c.getStage());
        assertEquals(80, first.getIntentionBefore());
        assertEquals(53, first.getIntentionAfter());
        assertEquals(-27, first.getIntentionChange());
        when(records.selectList(any())).thenReturn(List.of(first));
        var next = service.create(7L, command("有兴趣", null, "AUTO", null));
        assertEquals(53, next.getIntentionAfter());
        assertEquals(0, next.getIntentionChange());
        verify(records, times(2)).insert(any(FollowRecord.class));
    }

    @Test
    void manualScorePersistsUntilExplicitlyReturningToAutomaticScoring() {
        Customer c = scoringCustomer();
        var manual = service.create(7L, command("准备成交", null, "MANUAL", 0));
        assertEquals(0, manual.getIntentionAfter());
        assertEquals("MANUAL", c.getIntentionMode());
        var retained = service.create(7L, command("有兴趣", null, null, null));
        assertEquals(0, retained.getIntentionAfter());
        var automatic = service.create(7L, command("有兴趣", null, "AUTO", null));
        assertEquals(53, automatic.getIntentionAfter());
        assertEquals("AUTO", c.getIntentionMode());
    }

    @Test
    void lostFeedbackSetsTerminalStatusAndUnansweredSignalsAreIgnored() {
        Customer c = scoringCustomer();
        var attempt = service.create(7L, new FollowService.FollowCommand(8L, null, "微信", "客户没有回复", "没有回应", null,
                100, true, true, null, "AUTO", null));
        assertFalse(attempt.getActiveInquiry()); assertFalse(attempt.getPriceInquiry());
        assertEquals(30, attempt.getIntentionAfter());
        var lost = service.create(7L, command("已流失", null, "AUTO", null));
        assertEquals("LOST", c.getStatus()); assertEquals(0, lost.getIntentionAfter());
        verify(tasks, never()).insert(any(FollowTask.class));
    }

    @Test
    void invalidManualScoreOrMissingCustomerCannotPersistRecords() {
        scoringCustomer();
        assertThrows(ApiException.class, () -> service.create(7L, command("有兴趣", null, "MANUAL", 101)));
        verify(records, never()).insert(any(FollowRecord.class));
        verify(customers, never()).updateById(any(Customer.class));
        when(customers.selectOne(any())).thenReturn(null);
        assertThrows(ApiException.class, () -> service.create(7L, command("有兴趣", null, "AUTO", null)));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void scoringHistoryAndLockedCustomerAreLimitedToCurrentOwner() {
        scoringCustomer();
        service.create(7L, command("有兴趣", null, "AUTO", null));
        ArgumentCaptor<LambdaQueryWrapper<Customer>> customerQuery = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(customers).selectOne(customerQuery.capture());
        assertTrue(customerQuery.getValue().getSqlSegment().endsWith("FOR UPDATE"));
        assertTrue(customerQuery.getValue().getParamNameValuePairs().containsValue(7L));
        ArgumentCaptor<LambdaQueryWrapper<FollowRecord>> historyQuery = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(records).selectList(historyQuery.capture());
        assertTrue(historyQuery.getValue().getSqlSegment().contains("user_id ="));
        assertTrue(historyQuery.getValue().getSqlSegment().contains("customer_id ="));
        assertTrue(historyQuery.getValue().getSqlSegment().contains("created_at >="));
        assertTrue(historyQuery.getValue().getParamNameValuePairs().containsValue(7L));
        assertTrue(historyQuery.getValue().getParamNameValuePairs().containsValue(8L));
    }

    private Customer scoringCustomer() {
        Customer c = new Customer(); c.setId(8L); c.setUserId(7L); c.setStatus("ACTIVE");
        c.setStage("报价中"); c.setIntentionScore(80); c.setIntentionMode("AUTO"); c.setFollowCount(0);
        when(customers.selectOne(any())).thenReturn(c);
        return c;
    }

    private FollowService.FollowCommand command(String feedback, String status, String mode, Integer score) {
        return new FollowService.FollowCommand(8L, null, "微信", "客户反馈", feedback, status, 20, false, false, score, mode, null);
    }

    private FollowTask task(Long customerId) {
        FollowTask task = new FollowTask(); task.setId(10L); task.setUserId(7L);
        task.setCustomerId(customerId); task.setPlanTime(LocalDate.now(AiQuota.ZONE).plusDays(1).atStartOfDay());
        return task;
    }
}
