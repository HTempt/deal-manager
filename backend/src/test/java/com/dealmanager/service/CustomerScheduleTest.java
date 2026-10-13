package com.dealmanager.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dealmanager.common.ApiException;
import com.dealmanager.domain.Customer;
import com.dealmanager.domain.FollowTask;
import com.dealmanager.domain.FollowRecord;
import com.dealmanager.mapper.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CustomerScheduleTest {
    private CustomerMapper customers;
    private FollowTaskMapper tasks;
    private FollowRecordMapper records;
    private CustomerService service;
    private Customer customer;
    private LocalDateTime plan;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), Customer.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), FollowTask.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), FollowRecord.class);
        customers = mock(CustomerMapper.class);
        tasks = mock(FollowTaskMapper.class);
        records = mock(FollowRecordMapper.class);
        service = new CustomerService(customers, records, tasks, mock(SettingsService.class));
        customer = new Customer(); customer.setId(8L); customer.setUserId(7L);
        customer.setName("客户"); customer.setNeed("需求"); customer.setStatus("ACTIVE");
        customer.setStage("报价中"); customer.setFollowCount(3); customer.setIntentionScore(80);
        when(customers.selectOne(any())).thenReturn(customer);
        when(records.selectList(any())).thenReturn(List.of());
        plan = LocalDateTime.now(AiQuota.ZONE).plusDays(3).withNano(0);
    }

    @Test
    void editingKeepsManualScoreAndExplicitAutoModeRecalculatesFromCurrentFacts() {
        customer.setIntentionMode("MANUAL"); customer.setIntentionScore(72);
        service.update(7L, 8L, new CustomerService.CustomerCommand("客户", "杭州市", null, "", "需求", null, "微信", List.of(),
                "报价中", null, null, null));
        assertEquals(72, customer.getIntentionScore()); assertEquals("MANUAL", customer.getIntentionMode());
        service.update(7L, 8L, new CustomerService.CustomerCommand("客户", "杭州市", null, "", "需求", null, "微信", List.of(),
                "报价中", 99, "AUTO", null));
        assertEquals(30, customer.getIntentionScore()); assertEquals("AUTO", customer.getIntentionMode());
    }

    @Test
    void schedulingCreatesTaskWithoutChangingCustomerOrFollowHistory() {
        assertSame(customer, service.schedule(7L, 8L, plan));
        assertEquals(plan, customer.getNextFollowTime());
        assertEquals(3, customer.getFollowCount());
        assertEquals(80, customer.getIntentionScore());
        assertEquals("报价中", customer.getStage());
        assertNull(customer.getLastFollowTime());
        ArgumentCaptor<FollowTask> captured = ArgumentCaptor.forClass(FollowTask.class);
        verify(tasks).insert(captured.capture());
        assertEquals(plan, captured.getValue().getPlanTime());
        assertEquals(7L, captured.getValue().getUserId());
        assertEquals(8L, captured.getValue().getCustomerId());
        assertEquals("PENDING", captured.getValue().getStatus());
        verify(tasks).update(any(FollowTask.class), any());
        verifyNoInteractions(records);
    }

    @Test
    void identicalRequestDoesNotDuplicateTask() {
        service.schedule(7L, 8L, plan);
        service.schedule(7L, 8L, plan);
        verify(tasks, times(1)).insert(any(FollowTask.class));
        verify(customers, times(1)).updateById(customer);
    }

    @Test
    void replacingPlanCancelsExistingPendingTasks() {
        customer.setNextFollowTime(plan.minusDays(1));
        service.schedule(7L, 8L, plan);
        ArgumentCaptor<FollowTask> captured = ArgumentCaptor.forClass(FollowTask.class);
        verify(tasks).update(captured.capture(), any());
        assertEquals("CANCELLED", captured.getValue().getStatus());
        verify(tasks).insert(any(FollowTask.class));
    }

    @Test
    void missingAndTerminalCustomersCannotBeScheduled() {
        for (String status : new String[]{"DEAL", "LOST"}) {
            customer.setStatus(status);
            assertThrows(ApiException.class, () -> service.schedule(7L, 8L, plan));
        }
        when(customers.selectOne(any())).thenReturn(null);
        assertThrows(ApiException.class, () -> service.schedule(7L, 8L, plan));
        verifyNoInteractions(tasks, records);
        verify(customers, never()).updateById(any(Customer.class));
    }

    @Test
    void missingAndPastPlanAreRejectedBeforeAccessingCustomer() {
        assertThrows(ApiException.class, () -> service.schedule(7L, 8L, null));
        assertThrows(ApiException.class, () -> service.schedule(7L, 8L, plan.minusDays(4)));
        verifyNoInteractions(customers, tasks, records);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void scheduleLookupRequiresOwnerAndRowLock() {
        service.schedule(7L, 8L, plan);
        ArgumentCaptor<LambdaQueryWrapper<Customer>> captured = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(customers).selectOne(captured.capture());
        String sql = captured.getValue().getSqlSegment();
        assertTrue(sql.contains("user_id ="));
        assertTrue(sql.contains("id ="));
        assertTrue(sql.contains("status <>"));
        assertTrue(sql.endsWith("FOR UPDATE"));
        assertTrue(captured.getValue().getParamNameValuePairs().containsValue(7L));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void dormantFiltersUseCurrentOwnerActiveStatusAndMatchingFullDayCutoffs() {
        when(customers.selectPage(any(Page.class), any())).thenReturn(new Page<Customer>());
        for (String filter : new String[]{"DORMANT_ALL", "AT_RISK", "DORMANT", "LONG_DORMANT"}) {
            clearInvocations(customers);
            LocalDateTime before = LocalDateTime.now(AiQuota.ZONE);
            service.list(7L, "王", filter, 2, 20);
            LocalDateTime after = LocalDateTime.now(AiQuota.ZONE);
            ArgumentCaptor<LambdaQueryWrapper<Customer>> captured = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
            ArgumentCaptor<Page<Customer>> page = ArgumentCaptor.forClass(Page.class);
            verify(customers).selectPage(page.capture(), captured.capture());
            assertEquals(2, page.getValue().getCurrent()); assertEquals(20, page.getValue().getSize());
            var query = captured.getValue(); String sql = query.getSqlSegment();
            assertTrue(sql.contains("user_id =")); assertTrue(sql.contains("status ="));
            assertTrue(sql.contains("COALESCE(last_follow_time, created_at) <="));
            assertTrue(sql.contains("intention_score DESC,budget DESC"));
            assertTrue(sql.contains("name LIKE") && sql.contains("need_text LIKE"));
            var values = query.getParamNameValuePairs().values();
            assertTrue(values.contains(7L)); assertTrue(values.contains("ACTIVE")); assertTrue(values.contains("%王%"));
            int minimum = filter.equals("AT_RISK") ? 7 : filter.equals("LONG_DORMANT") ? 31 : 15;
            List<LocalDateTime> cutoffs = values.stream().filter(LocalDateTime.class::isInstance).map(LocalDateTime.class::cast).toList();
            assertTrue(cutoffs.stream().anyMatch(t -> !t.isBefore(before.minusDays(minimum)) && !t.isAfter(after.minusDays(minimum))));
            if (filter.equals("AT_RISK") || filter.equals("DORMANT")) {
                int upper = filter.equals("AT_RISK") ? 15 : 31;
                assertEquals(2, cutoffs.size()); assertTrue(sql.contains("COALESCE(last_follow_time, created_at) >"));
                assertTrue(cutoffs.stream().anyMatch(t -> !t.isBefore(before.minusDays(upper)) && !t.isAfter(after.minusDays(upper))));
            } else assertEquals(1, cutoffs.size());
        }
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void listDecoratesInactivityWithoutPersistingDerivedStatus() {
        customer.setLastFollowTime(LocalDateTime.now(AiQuota.ZONE).minusDays(18));
        Page<Customer> page = new Page<>(); page.setRecords(java.util.List.of(customer)); page.setTotal(1);
        when(customers.selectPage(any(Page.class), any())).thenReturn(page);
        var result = service.list(7L, null, "DORMANT_ALL", 1, 20);
        assertEquals(18L, result.getRecords().get(0).getDaysWithoutFollow());
        assertEquals("DORMANT", result.getRecords().get(0).getDormancyLevel());
        assertEquals("ACTIVE", customer.getStatus());
        verify(customers, never()).updateById(any(Customer.class));
        assertTrue(TableInfoHelper.getTableInfo(Customer.class).getFieldList().stream()
                .noneMatch(field -> java.util.Set.of("daysWithoutFollow", "dormancyLevel", "hasContactHistory").contains(field.getProperty())));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void unscheduledFilterUsesNullPlanActiveStatusAndCurrentUser() {
        when(customers.selectPage(any(Page.class), any())).thenReturn(new Page<Customer>());
        service.list(7L, null, "UNSCHEDULED", 1, 20);
        ArgumentCaptor<LambdaQueryWrapper<Customer>> captured = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(customers).selectPage(any(Page.class), captured.capture());
        String sql = captured.getValue().getSqlSegment();
        assertTrue(sql.contains("next_follow_time IS NULL"));
        assertTrue(sql.contains("user_id ="));
        assertTrue(captured.getValue().getParamNameValuePairs().containsValue("ACTIVE"));
        assertTrue(captured.getValue().getParamNameValuePairs().containsValue(7L));
    }
}
