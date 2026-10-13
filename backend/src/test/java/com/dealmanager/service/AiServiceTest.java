package com.dealmanager.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.dealmanager.ai.ModelPoolClient;
import com.dealmanager.ai.FollowAdvice;
import com.dealmanager.ai.AiPreferences;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import com.dealmanager.common.ApiException;
import com.dealmanager.domain.*;
import com.dealmanager.mapper.AiRecordMapper;
import com.dealmanager.mapper.FollowRecordMapper;
import com.dealmanager.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AiServiceTest {
    private AiRecordMapper aiRecords;
    private FollowRecordMapper followRecords;
    private CustomerService customers;
    private SettingsService settings;
    private UserMapper users;
    private ModelPoolClient models;
    private AiService service;
    private User user;
    private Customer customer;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), FollowRecord.class);
        aiRecords = mock(AiRecordMapper.class);
        followRecords = mock(FollowRecordMapper.class);
        customers = mock(CustomerService.class);
        settings = mock(SettingsService.class);
        users = mock(UserMapper.class);
        models = mock(ModelPoolClient.class);
        service = new AiService(aiRecords, followRecords, customers, settings, users, models, new ObjectMapper());
        user = new User();
        user.setId(7L);
        user.setMembershipType("FREE");
        customer = new Customer();
        customer.setId(8L);
        customer.setUserId(7L);
        customer.setName("客户");
        customer.setStatus("ACTIVE");
        customer.setCreatedAt(LocalDateTime.now().minusDays(1));
        UserAiSetting setting = new UserAiSetting();
        setting.setReplyStyle("自然");
        setting.setReplyLength("适中");
        setting.setPreferredAddress("您");
        when(users.selectOne(any())).thenReturn(user);
        when(aiRecords.selectCount(any())).thenReturn(0L);
        when(customers.get(7L, 8L)).thenReturn(customer);
        when(settings.aiSetting(7L)).thenReturn(setting);
        when(followRecords.selectList(any())).thenReturn(List.of());
    }

    @Test
    void freeUserAtTenCannotCallModelOrInsert() {
        when(aiRecords.selectCount(any())).thenReturn((long) AiQuota.FREE_DAILY_LIMIT);
        ApiException error = assertThrows(ApiException.class,
                () -> service.generate(7L, 8L, "报价后", "GENERATE", null));
        assertEquals(429, error.getStatus().value());
        verifyNoInteractions(models);
        verify(aiRecords, never()).insert(any(AiRecord.class));
    }

    @Test
    void vipIsUnlimitedEvenWhenTenRecordsUsed() {
        user.setMembershipType("VIP");
        user.setMembershipExpireTime(LocalDateTime.now(AiQuota.ZONE).plusDays(1));
        when(aiRecords.selectCount(any())).thenReturn(10L);
        when(models.generate(anyString(), eq("ASSISTANT_GENERATE")))
                .thenReturn(new ModelPoolClient.ModelResult(advice("有效话术", 2), "gpt-test", 23));
        AiService.AiResult result = service.generate(7L, 8L, "报价后", "GENERATE", null);
        assertEquals("有效话术", result.content());
        assertTrue(result.unlimited());
        assertEquals(-1, result.remainingToday());
        verify(models).generate(anyString(), eq("ASSISTANT_GENERATE"));
        verify(aiRecords).insert(any(AiRecord.class));
    }

    @Test
    void expiredVipUsesFreeLimit() {
        user.setMembershipType("VIP");
        user.setMembershipExpireTime(LocalDateTime.now(AiQuota.ZONE).minusSeconds(1));
        when(aiRecords.selectCount(any())).thenReturn(10L);
        assertThrows(ApiException.class, () -> service.generate(7L, 8L, "跟进", "GENERATE", null));
        verifyNoInteractions(models);
        verify(aiRecords, never()).insert(any(AiRecord.class));
    }

    @Test
    void regenerateSameContentDoesNotInsert() {
        when(models.generate(anyString(), eq("ASSISTANT_REGENERATE")))
                .thenReturn(new ModelPoolClient.ModelResult(advice("旧  话术", 2), "m", 4));
        assertThrows(ApiException.class,
                () -> service.generate(7L, 8L, "跟进", "REGENERATE", "旧话术"));
        verify(aiRecords, never()).insert(any(AiRecord.class));
    }

    @Test
    void shortenContentThatIsNotShorterDoesNotInsert() {
        when(models.generate(anyString(), eq("ASSISTANT_SHORTEN")))
                .thenReturn(new ModelPoolClient.ModelResult(advice("这是一段很长的话术", 2), "m", 5));
        assertThrows(ApiException.class,
                () -> service.generate(7L, 8L, "跟进", "SHORTEN", "短"));
        verify(aiRecords, never()).insert(any(AiRecord.class));
    }

    @Test
    void modelErrorDoesNotInsert() {
        when(models.generate(anyString(), eq("ASSISTANT_GENERATE")))
                .thenThrow(new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "模型错误"));
        assertThrows(ApiException.class,
                () -> service.generate(7L, 8L, "跟进", "GENERATE", null));
        verify(aiRecords, never()).insert(any(AiRecord.class));
    }

    @Test
    void successfulCallInsertsActualModelAndTokenUsage() {
        when(models.generate(anyString(), eq("ASSISTANT_GENERATE")))
                .thenReturn(new ModelPoolClient.ModelResult(advice("成功话术", 2), "actual-model", 137));
        AiService.AiResult result = service.generate(7L, 8L, "跟进", "GENERATE", null);
        assertEquals("成功话术", result.content());
        assertEquals(9, result.remainingToday());
        ArgumentCaptor<AiRecord> captor = ArgumentCaptor.forClass(AiRecord.class);
        verify(aiRecords).insert(captor.capture());
        assertEquals("actual-model", captor.getValue().getModel());
        assertEquals(137, captor.getValue().getTokenUsage());
        assertEquals(7L, captor.getValue().getUserId());
        assertEquals(8L, captor.getValue().getCustomerId());
    }

    @Test
    void structuredAdviceReturnsModelJudgmentAndSchedulesTwoDaysLaterWithoutCreatingTask() {
        when(models.generate(anyString(), eq("ASSISTANT_GENERATE")))
                .thenReturn(new ModelPoolClient.ModelResult(advice("请问您主要顾虑总价还是项目范围？", 2), "m", 23));
        AiService.AiResult result = service.generate(7L, 8L, "客户嫌贵", "GENERATE", null);
        assertEquals("客户有需求，价格是主要顾虑", result.assessment());
        assertEquals("先了解预算和项目顾虑", result.suggestedAction());
        assertEquals(2, result.followUpDays());
        assertEquals(java.time.LocalDate.now(AiQuota.ZONE).plusDays(2).atTime(10, 0), result.nextFollowTime());
        assertEquals(9, result.remainingToday());
        verify(customers, never()).schedule(anyLong(), anyLong(), any());
        ArgumentCaptor<AiRecord> record = ArgumentCaptor.forClass(AiRecord.class);
        verify(aiRecords).insert(record.capture());
        assertEquals(result.assessment(), FollowAdvice.parse(record.getValue().getResponse()).assessment());
    }

    @Test
    void malformedIncompleteAndInvalidIntervalsDoNotConsumeQuota() {
        for (String value : List.of("一句话", "{}", advice("话术", 0), advice("话术", 31), advice("话术", null))) {
            when(models.generate(anyString(), eq("ASSISTANT_GENERATE")))
                    .thenReturn(new ModelPoolClient.ModelResult(value, "m", 1));
            assertThrows(ApiException.class, () -> service.generate(7L, 8L, "客户嫌贵", "GENERATE", null));
        }
        verify(aiRecords, never()).insert(any(AiRecord.class));
    }

    @Test
    void successfulShorteningChecksOnlyScriptAndReturnsCompleteAdvice() {
        when(models.generate(anyString(), eq("ASSISTANT_SHORTEN")))
                .thenReturn(new ModelPoolClient.ModelResult(advice("预算如何？", 5), "m", 20));
        var result = service.generate(7L, 8L, "客户嫌贵", "SHORTEN", "请问您主要顾虑总价还是项目范围？");
        assertEquals("预算如何？", result.content());
        assertEquals(5, result.followUpDays());
        verify(aiRecords).insert(any(AiRecord.class));
    }

    @Test
    void terminalCustomersDoNotReceiveAdoptableTime() {
        for (String status : List.of("DEAL", "LOST")) {
            customer.setStatus(status);
            String modelReason = "DEAL".equals(status) ? "客户已成交，应围绕交付时间提供服务" : "客户已放弃本次购买，应尊重决定";
            String response = advice("谢谢反馈", null).replace("给客户留出梳理预算的时间", modelReason);
            when(models.generate(anyString(), eq("ASSISTANT_GENERATE")))
                    .thenReturn(new ModelPoolClient.ModelResult(response, "m", 20));
            var result = service.generate(7L, 8L, "报价后", "GENERATE", null);
            assertNull(result.followUpDays());
            assertNull(result.nextFollowTime());
            assertEquals(modelReason, result.followUpReason());
        }
    }

    @Test
    void everySceneReturnsAllAdviceFieldsFromModel() {
        for (String scenario : List.of("首次跟进", "报价后跟进", "客户嫌贵", "客户不回复", "促成成交", "唤醒客户")) {
            String response = advice("模型针对" + scenario + "生成的话术", 4);
            when(models.generate(anyString(), eq("ASSISTANT_GENERATE")))
                    .thenReturn(new ModelPoolClient.ModelResult(response, "m", 20));
            var result = service.generate(7L, 8L, scenario, "GENERATE", null);
            FollowAdvice expected = FollowAdvice.parse(response);
            assertEquals(expected.assessment(), result.assessment());
            assertEquals(expected.suggestedAction(), result.suggestedAction());
            assertEquals(expected.content(), result.content());
            assertEquals(expected.followUpDays(), result.followUpDays());
            assertEquals(expected.followUpReason(), result.followUpReason());
        }
        verify(models, times(6)).generate(anyString(), eq("ASSISTANT_GENERATE"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void promptContainsRecentFeedbackAndDoesNotExposeAnotherCustomersRecords() {
        FollowRecord recent = new FollowRecord();
        recent.setContent("觉得报价有点高");
        recent.setCustomerFeedback("考虑中");
        recent.setActiveInquiry(true); recent.setPriceInquiry(true);
        customer.setTags(List.of("价格敏感", "决策人"));
        customer.setNeed("厨房装修"); customer.setBudget(new java.math.BigDecimal("80000"));
        recent.setCreatedAt(LocalDateTime.now());
        when(followRecords.selectList(any())).thenReturn(List.of(recent));
        when(models.generate(anyString(), eq("ASSISTANT_GENERATE")))
                .thenReturn(new ModelPoolClient.ModelResult(advice("可以了解预算", 2), "m", 20));
        service.generate(7L, 8L, "客户嫌贵", "GENERATE", null);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(models).generate(prompt.capture(), eq("ASSISTANT_GENERATE"));
        assertTrue(prompt.getValue().contains("觉得报价有点高"));
        assertTrue(prompt.getValue().contains("考虑中"));
        assertTrue(prompt.getValue().contains("最近5次跟进"));
        assertTrue(prompt.getValue().contains("客户状态：ACTIVE"));
        assertTrue(prompt.getValue().contains("人工标签")); assertTrue(prompt.getValue().contains("决策人"));
        assertTrue(prompt.getValue().contains("\"activeInquiry\":true"));
        assertTrue(prompt.getValue().contains("\"priceInquiry\":true"));
        assertTrue(prompt.getValue().contains("需求：厨房装修")); assertTrue(prompt.getValue().contains("预算：80000"));
        assertTrue(prompt.getValue().contains("不要笼统重复要求确认需求和预算"));
        ArgumentCaptor<LambdaQueryWrapper<FollowRecord>> query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(followRecords).selectList(query.capture());
        assertTrue(query.getValue().getSqlSegment().contains("user_id ="));
        assertTrue(query.getValue().getSqlSegment().contains("customer_id ="));
        assertTrue(query.getValue().getSqlSegment().endsWith("LIMIT 5"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(7L));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(8L));
    }

    @Test
    void everyAssistantOperationReadsLatestUnifiedPreferences() {
        for (String action : List.of("GENERATE", "REGENERATE", "SHORTEN")) {
            UserAiSetting preferences = new UserAiSetting();
            preferences.setReplyStyle("专业正式"); preferences.setReplyLength("详细"); preferences.setPreferredAddress("您");
            preferences.setBusinessIntro("住宅装修" + action); preferences.setBusinessAdvantage("报价透明");
            preferences.setCommonExpression("为您确认");
            when(settings.aiSetting(7L)).thenReturn(preferences);
            when(models.generate(anyString(), eq("ASSISTANT_" + action)))
                    .thenReturn(new ModelPoolClient.ModelResult(advice("新的话术", 2), "m", 20));
            service.generate(7L, 8L, "跟进", action, "这是一段很长的上一版客户跟进话术");
            ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
            verify(models).generate(prompt.capture(), eq("ASSISTANT_" + action));
            assertTrue(prompt.getValue().contains(AiPreferences.from(preferences).toJson()));
            assertTrue(prompt.getValue().contains("globalAiSettings"));
        }
        verify(settings, times(3)).aiSetting(7L);
    }

    @Test
    void wakeupUsesActualInactivityBudgetFeedbackAndUnifiedStyleWithNormalQuota() {
        customer.setLastFollowTime(LocalDateTime.now(AiQuota.ZONE).minusDays(18));
        customer.setFollowCount(5); customer.setStage("报价中"); customer.setBudget(new java.math.BigDecimal("38000"));
        FollowRecord record = new FollowRecord(); record.setContent("报价需要再和家人商量");
        when(followRecords.selectList(any())).thenReturn(List.of(record));
        when(models.generate(anyString(), eq("ASSISTANT_GENERATE")))
                .thenReturn(new ModelPoolClient.ModelResult(advice("您好，想确认装修需求还在吗？", 2), "m", 20));
        var result = service.generate(7L, 8L, "唤醒客户", "GENERATE", null);
        assertEquals(9, result.remainingToday());
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(models).generate(prompt.capture(), eq("ASSISTANT_GENERATE"));
        assertTrue(prompt.getValue().contains("距上次联系：18天"));
        assertTrue(prompt.getValue().contains("沉睡分组：DORMANT"));
        assertTrue(prompt.getValue().contains("预算：38000"));
        assertTrue(prompt.getValue().contains("报价需要再和家人商量"));
        assertTrue(prompt.getValue().contains("为什么现在值得联系"));
        assertTrue(prompt.getValue().contains("具体唤醒方式和切入点"));
        assertTrue(prompt.getValue().contains("globalAiSettings"));
        verify(customers, never()).schedule(anyLong(), anyLong(), any());
        verify(aiRecords).insert(any(AiRecord.class));
    }

    @Test
    void wakeupRejectsTerminalAndInaccessibleCustomersWithoutCallingModel() {
        for (String status : List.of("DEAL", "LOST")) {
            customer.setStatus(status);
            assertThrows(ApiException.class, () -> service.generate(7L, 8L, "唤醒客户", "GENERATE", null));
        }
        when(customers.get(7L, 8L)).thenThrow(ApiException.notFound("客户不存在"));
        assertThrows(ApiException.class, () -> service.generate(7L, 8L, "唤醒客户", "GENERATE", null));
        verifyNoInteractions(models); verify(aiRecords, never()).insert(any(AiRecord.class));
    }

    private static String advice(String content, Integer days) {
        try {
            Map<String, Object> fields = new java.util.LinkedHashMap<>();
            fields.put("assessment", "客户有需求，价格是主要顾虑");
            fields.put("suggestedAction", "先了解预算和项目顾虑");
            fields.put("content", content);
            fields.put("followUpDays", days);
            fields.put("followUpReason", "给客户留出梳理预算的时间");
            return new ObjectMapper().writeValueAsString(fields);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
