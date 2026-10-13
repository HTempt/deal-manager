package com.dealmanager.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dealmanager.domain.*;
import com.dealmanager.mapper.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CustomerFunnelBusinessTest {
    CustomerMapper customers;
    FollowRecordMapper records;
    FollowTaskMapper tasks;
    CustomerService service;

    @BeforeEach
    void setUp() {
        for (Class<?> type : List.of(Customer.class, FollowRecord.class, FollowTask.class))
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), type);
        customers = mock(CustomerMapper.class); records = mock(FollowRecordMapper.class); tasks = mock(FollowTaskMapper.class);
        SettingsService settings = mock(SettingsService.class);
        User user = new User(); user.setMembershipType("PRO"); when(settings.user(7L)).thenReturn(user);
        service = new CustomerService(customers, records, tasks, settings);
        when(records.selectList(any())).thenReturn(List.of());
    }

    @Test
    void createAndUpdateAdvanceBeforeAndAfterStageChangesWithoutFakingDeal() {
        Customer created = service.create(7L, command("报价中"));
        assertEquals(3, created.getFunnelStageRank()); assertEquals("ACTIVE", created.getStatus());
        when(customers.selectOne(any())).thenReturn(created);
        service.update(7L, 8L, command("需求沟通")); assertEquals(3, created.getFunnelStageRank());
        service.update(7L, 8L, command("谈判中")); assertEquals(4, created.getFunnelStageRank());
        service.update(7L, 8L, command("已成交")); assertEquals(4, created.getFunnelStageRank()); assertEquals("ACTIVE", created.getStatus());
        created.setFunnelStageRank(null); created.setStage("报价中");
        service.update(7L, 8L, command("新客户")); assertEquals(3, created.getFunnelStageRank());
        assertEquals(0, service.create(7L, command("已成交")).getFunnelStageRank());
    }

    @Test
    void followPreservesPreLossRankAndAdvancesNewStageBeforeLostFeedbackOverwritesIt() {
        Customer c = customer("报价中"); when(customers.selectOne(any())).thenReturn(c);
        FollowService follows = new FollowService(tasks, records, customers, service);
        follows.create(7L, follow("有兴趣", "需求沟通")); assertEquals(3, c.getFunnelStageRank());
        follows.create(7L, follow("已流失", "谈判中"));
        assertEquals("LOST", c.getStatus()); assertEquals("已流失", c.getStage()); assertEquals(4, c.getFunnelStageRank());
        verify(customers, times(2)).updateById(c);
        Customer fake = customer("新客户"); when(customers.selectOne(any())).thenReturn(fake);
        follows.create(7L, follow("准备成交", "已成交"));
        assertEquals("ACTIVE", fake.getStatus()); assertEquals(0, fake.getFunnelStageRank());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void realDealLocksOwnerRowAndPersistsRankFiveIncludingWhenSkippingQuote() {
        Customer c = customer("新客户"); when(customers.selectOne(any())).thenReturn(c);
        DealMapper deals = mock(DealMapper.class);
        new DealService(deals, customers, tasks, service).create(7L, 8L, BigDecimal.TEN, "产品", null);
        assertEquals("DEAL", c.getStatus()); assertEquals(5, c.getFunnelStageRank());
        verify(customers).updateById(c); verify(deals).insert(any(Deal.class));
        var query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(customers).selectOne(query.capture());
        assertTrue(query.getValue().getSqlSegment().endsWith("FOR UPDATE"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(7L));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(8L));
    }

    private Customer customer(String stage) {
        Customer c = new Customer(); c.setId(8L); c.setUserId(7L); c.setStage(stage); c.setStatus("ACTIVE");
        c.setIntentionScore(10); c.setFollowCount(0); return c;
    }
    private CustomerService.CustomerCommand command(String stage) {
        return new CustomerService.CustomerCommand("客户", "杭州市", null, "", "需求", null, "微信", List.of(), stage, null, "AUTO", null);
    }
    private FollowService.FollowCommand follow(String feedback, String stage) {
        return new FollowService.FollowCommand(8L, null, "微信", "客户反馈", feedback, stage, null, false, false, null, "AUTO", null);
    }
}
