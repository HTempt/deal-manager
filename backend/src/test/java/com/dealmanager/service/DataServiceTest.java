package com.dealmanager.service;

import com.dealmanager.domain.Deal;
import com.dealmanager.mapper.CustomerMapper;
import com.dealmanager.mapper.DealMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DataServiceTest {
    @Test
    void multipleDealsForSameCustomerCountOnceButAllAmountsAreIncluded() {
        CustomerMapper customers = mock(CustomerMapper.class);
        DealMapper deals = mock(DealMapper.class);
        when(customers.selectCount(any())).thenReturn(2L);
        when(customers.selectList(any())).thenReturn(List.of());
        when(deals.selectList(any())).thenReturn(List.of(deal(1L, "100"), deal(1L, "200"), deal(2L, "300")));
        DataService service = new DataService(customers, deals);
        var overview = service.overview(7L, YearMonth.of(2026, 10));
        assertEquals(2, overview.dealCustomers());
        assertEquals(new BigDecimal("600"), overview.dealAmount());
        var sources = service.sources(7L, YearMonth.of(2026, 10));
        assertEquals(1, sources.size());
        assertEquals(2, sources.get(0).dealCustomers());
        assertEquals(new BigDecimal("600"), sources.get(0).dealAmount());
    }

    @Test
    void funnelCountsCustomersNotDealRecordsAndUsesSameCohortForBothRates() {
        CustomerMapper customers = mock(CustomerMapper.class);
        DealMapper deals = mock(DealMapper.class);
        var cohort = new java.util.ArrayList<com.dealmanager.domain.Customer>();
        for (int i = 0; i < 126; i++) cohort.add(customer(i < 12 ? "DEAL" : i == 12 ? "LOST" : "ACTIVE", i < 43 ? 3 : 0));
        when(customers.selectList(any())).thenReturn(cohort);
        var result = new DataService(customers, deals).funnel(7L, YearMonth.of(2026, 10));
        assertEquals(List.of(126L, 43L, 43L, 43L, 12L, 12L), result.stages().stream().map(DataService.FunnelStage::customers).toList());
        assertEquals(List.of("新增客户", "需求沟通", "方案中", "报价中", "谈判中", "成交"), result.stages().stream().map(DataService.FunnelStage::stage).toList());
        assertEquals(1, result.lostCustomers());
        assertEquals(new BigDecimal("9.5"), result.dealConversionPercent());
        assertEquals(new BigDecimal("27.9"), result.quoteToDealPercent());
        assertEquals(new BigDecimal("34.1"), result.inquiryToQuotePercent());
        assertEquals("所选月份新增且未删除的客户", result.cohortBasis());
        assertEquals("截至当前的累计阶段进展，跳阶段计入前置阶段", result.progressBasis());
        verifyNoInteractions(deals); // Other cohorts' deals and repeat deals cannot enter either numerator.
        verify(customers, never()).updateById(any(com.dealmanager.domain.Customer.class));
    }

    @Test
    void emptyAndUnquotedCohortsReturnNullOnlyForZeroDenominators() {
        CustomerMapper customers = mock(CustomerMapper.class);
        DataService service = new DataService(customers, mock(DealMapper.class));
        when(customers.selectList(any())).thenReturn(List.of());
        var empty = service.funnel(7L, YearMonth.of(2026, 10));
        assertNull(empty.dealConversionPercent()); assertNull(empty.quoteToDealPercent());
        assertNull(empty.inquiryToQuotePercent());
        assertTrue(empty.stages().stream().allMatch(s -> s.customers() == 0));
        var fake = customer("ACTIVE", 0); fake.setStage("已成交");
        when(customers.selectList(any())).thenReturn(List.of(fake));
        var unquoted = service.funnel(7L, YearMonth.of(2026, 10));
        assertEquals(new BigDecimal("0.0"), unquoted.dealConversionPercent());
        assertEquals(new BigDecimal("0.0"), unquoted.inquiryToQuotePercent());
        assertNull(unquoted.quoteToDealPercent()); assertEquals(0, unquoted.stages().get(5).customers());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void funnelQueryUsesOwnerCreationMonthHalfOpenBoundsAndExcludesDeletedNotLost() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "test"), com.dealmanager.domain.Customer.class);
        CustomerMapper customers = mock(CustomerMapper.class);
        when(customers.selectList(any())).thenReturn(List.of(customer("LOST", 4)));
        var result = new DataService(customers, mock(DealMapper.class)).funnel(7L, YearMonth.of(2026, 10));
        var captor = org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(customers).selectList(captor.capture());
        var query = captor.getValue(); String sql = query.getSqlSegment();
        assertTrue(sql.contains("user_id =")); assertTrue(sql.contains("created_at >="));
        assertTrue(sql.contains("created_at <")); assertTrue(sql.contains("status <>"));
        var params = query.getParamNameValuePairs().values();
        assertTrue(params.contains(7L)); assertTrue(params.contains("DELETED"));
        assertTrue(params.contains(java.time.LocalDateTime.of(2026, 10, 1, 0, 0)));
        assertTrue(params.contains(java.time.LocalDateTime.of(2026, 11, 1, 0, 0)));
        assertFalse(sql.contains("deal_time")); assertEquals(1, result.stages().get(4).customers());
        assertEquals(0, result.stages().get(5).customers());
    }

    private com.dealmanager.domain.Customer customer(String status, int rank) {
        var customer = new com.dealmanager.domain.Customer();
        customer.setStatus(status); customer.setStage("新客户"); customer.setFunnelStageRank(rank);
        return customer;
    }

    private Deal deal(Long customerId, String amount) {
        Deal deal = new Deal();
        deal.setCustomerId(customerId);
        deal.setAmount(new BigDecimal(amount));
        deal.setSource("微信");
        return deal;
    }
}
