package com.dealmanager.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dealmanager.ai.ModelPoolClient;
import com.dealmanager.common.ApiException;
import com.dealmanager.domain.*;
import com.dealmanager.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CustomerSummaryServiceTest {
    private final CustomerService customers = mock(CustomerService.class);
    private final UserMapper users = mock(UserMapper.class);
    private final FollowRecordMapper follows = mock(FollowRecordMapper.class);
    private final CustomerSummaryMapper summaries = mock(CustomerSummaryMapper.class);
    private final ModelPoolClient models = mock(ModelPoolClient.class);
    private final ObjectMapper json = new ObjectMapper();
    private final SettingsService settings = mock(SettingsService.class);
    private UserAiSetting preferences;
    private CustomerSummaryService service;
    private Customer customer;
    private final String brief = "{\"overview\":\"装修客户，预算15万元，报价中，关注价格。\",\"suggestion\":\"先确认预算顾虑，建议2天后联系。\"}";

    @BeforeEach
    void setUp() {
        for (Class<?> type : List.of(User.class, FollowRecord.class, CustomerSummary.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), type);
        }
        service = new CustomerSummaryService(customers, users, follows, summaries, models, json, settings);
        preferences = new UserAiSetting(); preferences.setReplyStyle("专业正式"); preferences.setReplyLength("详细");
        preferences.setPreferredAddress("您"); preferences.setBusinessIntro("住宅装修");
        preferences.setBusinessAdvantage("报价透明"); preferences.setCommonExpression("为您确认");
        when(settings.aiSetting(7L)).thenReturn(preferences);
        User user = new User(); user.setId(7L); user.setIndustry("装修");
        customer = new Customer(); customer.setId(8L); customer.setUserId(7L);
        customer.setName("王先生"); customer.setNeed("装修"); customer.setBudget(new BigDecimal("150000"));
        customer.setStage("报价中"); customer.setStatus("ACTIVE");
        customer.setCreatedAt(LocalDateTime.now(AiQuota.ZONE).minusDays(10).withNano(0));
        customer.setLastFollowTime(LocalDateTime.now(AiQuota.ZONE).minusDays(4).withNano(0));
        when(users.selectOne(any())).thenReturn(user);
        when(customers.get(7L, 8L)).thenReturn(customer);
        when(follows.selectCount(any())).thenReturn(5L);
        when(follows.selectList(any())).thenReturn(List.of(follow(5, "需要和家人商量"), follow(4, "觉得报价有点高")));
        when(models.generate(anyString(), eq("CUSTOMER_SUMMARY")))
                .thenReturn(new ModelPoolClient.ModelResult(brief, "test-model", 42));
    }

    @Test
    void firstGenerationPersistsSummaryAndUsesLatestFeedbackAndRealTotals() throws Exception {
        var result = service.summarize(7L, 8L, false);
        assertEquals(5, result.followCount());
        assertEquals(4, result.daysWithoutContact());
        assertTrue(result.hasContactHistory());
        assertFalse(result.stale());
        assertEquals(2, result.recordsUsed());
        ArgumentCaptor<CustomerSummary> captured = ArgumentCaptor.forClass(CustomerSummary.class);
        verify(summaries).insert(captured.capture());
        CustomerSummary saved = captured.getValue();
        assertEquals(7L, saved.getUserId()); assertEquals(8L, saved.getCustomerId());
        assertEquals(result.overview(), saved.getOverview());
        assertEquals(result.suggestion(), saved.getSuggestion());
        assertEquals("test-model", saved.getModel()); assertEquals(42, saved.getTokenUsage());
        assertEquals(64, saved.getSourceHash().length());
        assertNotNull(saved.getCreatedAt()); assertEquals(saved.getCreatedAt(), saved.getUpdatedAt());
        var snapshot = json.readTree(saved.getPrompt().substring(saved.getPrompt().indexOf('{')));
        assertTrue(result.aiGenerated());
        var facts = snapshot.get("customerFacts");
        var seller = snapshot.get("sellerContext");
        assertEquals(150000, facts.get("budget").asInt());
        assertEquals("装修", seller.get("businessIndustry").asText());
        assertFalse(facts.has("industry"));
        assertEquals(5, facts.get("followCount").asLong());
        assertEquals("需要和家人商量", facts.get("history").get(0).get("content").asText());
        assertEquals("觉得报价有点高", facts.get("history").get(1).get("content").asText());
        assertEquals("专业正式", seller.get("globalAiSettings").get("replyStyle").asText());
        assertEquals("详细", seller.get("globalAiSettings").get("replyLength").asText());
        assertEquals("您", seller.get("globalAiSettings").get("preferredAddress").asText());
        assertEquals("住宅装修", seller.get("globalAiSettings").get("businessIntro").asText());
        assertEquals("报价透明", seller.get("globalAiSettings").get("businessAdvantage").asText());
        assertEquals("为您确认", seller.get("globalAiSettings").get("commonExpression").asText());
    }

    @Test
    void cacheSurvivesServiceRecreationAndChangedSourceMarksStaleWithoutModelCall() {
        CustomerSummary saved = generateAndCache();
        clearInvocations(models, summaries);
        service = new CustomerSummaryService(customers, users, follows, summaries, models, json, settings);
        assertFalse(service.summarize(7L, 8L, false).stale());
        customer.setNeed("装修厨房");
        var result = service.summarize(7L, 8L, false);
        assertTrue(result.stale()); assertEquals(saved.getOverview(), result.overview());
        verifyNoInteractions(models);
        verify(summaries, never()).insert(any(CustomerSummary.class));
        verify(summaries, never()).updateById(any(CustomerSummary.class));
    }

    @Test
    void globalSettingChangesInvalidateCacheAndRefreshUsesLatestPreferences() throws Exception {
        CustomerSummary saved = generateAndCache(); clearInvocations(models);
        preferences.setReplyStyle("简洁直接"); preferences.setReplyLength("简短");
        assertTrue(service.summarize(7L, 8L, false).stale());
        verifyNoInteractions(models);
        assertFalse(service.summarize(7L, 8L, true).stale());
        var snapshot = json.readTree(saved.getPrompt().substring(saved.getPrompt().indexOf('{')));
        assertEquals("简洁直接", snapshot.get("sellerContext").get("globalAiSettings").get("replyStyle").asText());
        assertEquals("简短", snapshot.get("sellerContext").get("globalAiSettings").get("replyLength").asText());
    }

    @Test
    void tagsAndInquirySignalsInvalidateCacheAndExplicitRefreshUsesLatestFacts() throws Exception {
        CustomerSummary saved = generateAndCache(); clearInvocations(models);
        customer.setTags(List.of("价格敏感", "决策人"));
        FollowRecord recent = follow(6, "主动咨询厨房报价");
        recent.setActiveInquiry(true); recent.setPriceInquiry(true);
        when(follows.selectList(any())).thenReturn(List.of(recent));
        assertTrue(service.summarize(7L, 8L, false).stale()); verifyNoInteractions(models);
        when(models.generate(anyString(), eq("CUSTOMER_SUMMARY"))).thenReturn(new ModelPoolClient.ModelResult(
                "{\"overview\":\"客户主动咨询厨房报价\",\"suggestion\":\"确认厨房尺寸与预算\"}", "test-model", 42));
        var updated = service.summarize(7L, 8L, true);
        assertEquals("客户主动咨询厨房报价", updated.overview()); assertFalse(updated.stale());
        var snapshot = json.readTree(saved.getPrompt().substring(saved.getPrompt().indexOf('{')));
        var facts = snapshot.get("customerFacts");
        assertEquals("价格敏感", facts.get("tags").get(0).asText());
        assertTrue(facts.get("history").get(0).get("activeInquiry").asBoolean());
        assertTrue(facts.get("history").get(0).get("priceInquiry").asBoolean());
        assertTrue(saved.getPrompt().contains("不等同客户亲口反馈"));
        verify(summaries).updateById(saved);
    }

    @Test
    void explicitRefreshWithoutHistoryGeneratesAndCachesAdviceFromUpdatedProfile() throws Exception {
        when(follows.selectList(any())).thenReturn(List.of()); when(follows.selectCount(any())).thenReturn(0L);
        var original = service.summarize(7L, 8L, false);
        ArgumentCaptor<CustomerSummary> captured = ArgumentCaptor.forClass(CustomerSummary.class);
        verify(summaries).insert(captured.capture());
        CustomerSummary saved = captured.getValue();
        when(summaries.selectOne(any())).thenReturn(saved);
        customer.setNeed("厨房装修"); customer.setTags(List.of("预算8万"));
        customer.setBudget(new BigDecimal("80000"));
        when(models.generate(anyString(), eq("CUSTOMER_SUMMARY"))).thenReturn(new ModelPoolClient.ModelResult(
                "{\"overview\":\"厨房装修，预计金额8万元\",\"suggestion\":\"先了解厨房尺寸与施工时间，再提供8万元内的方案\"}", "test-model", 42));
        var refreshed = service.summarize(7L, 8L, true);
        assertTrue(refreshed.aiGenerated()); assertNotNull(refreshed.updatedAt());
        assertEquals(0, refreshed.recordsUsed());
        assertNotEquals(original.overview(), refreshed.overview());
        assertTrue(refreshed.overview().contains("厨房装修"));
        assertTrue(refreshed.suggestion().contains("厨房尺寸"));
        verify(summaries).updateById(saved);
        var facts = json.readTree(saved.getPrompt().substring(saved.getPrompt().indexOf('{'))).get("customerFacts");
        assertEquals("厨房装修", facts.get("need").asText());
        assertEquals(80000, facts.get("budget").asInt());
        assertEquals("预算8万", facts.get("tags").get(0).asText());
        assertTrue(facts.get("history").isEmpty());
        assertTrue(saved.getPrompt().contains("不要笼统重复要求确认需求和预算"));
        when(summaries.selectOne(any())).thenReturn(saved); clearInvocations(models);
        var cached = service.summarize(7L, 8L, false);
        assertTrue(cached.aiGenerated()); assertFalse(cached.stale());
        assertEquals(refreshed.suggestion(), cached.suggestion()); verifyNoInteractions(models);
        customer.setBudget(new BigDecimal("100000"));
        assertTrue(service.summarize(7L, 8L, false).stale()); verifyNoInteractions(models);
        clearInvocations(summaries);
        when(models.generate(anyString(), eq("CUSTOMER_SUMMARY"))).thenThrow(ApiException.badRequest("模型失败"));
        assertThrows(ApiException.class, () -> service.summarize(7L, 8L, true));
        assertEquals(refreshed.suggestion(), service.summarize(7L, 8L, false).suggestion());
        verify(summaries, never()).updateById(any(CustomerSummary.class));
    }

    @Test
    void newerFollowRecordsAndStatusChangesInvalidateCacheAndStatsRemainCurrent() {
        generateAndCache(); clearInvocations(models);
        when(follows.selectCount(any())).thenReturn(6L);
        when(follows.selectList(any())).thenReturn(List.of(follow(6, "确认成交")));
        customer.setLastFollowTime(LocalDateTime.now(AiQuota.ZONE)); customer.setStatus("DEAL");
        var result = service.summarize(7L, 8L, false);
        assertTrue(result.stale()); assertEquals(6, result.followCount()); assertEquals(0, result.daysWithoutContact());
        verifyNoInteractions(models);
    }

    @Test
    void explicitRefreshUpdatesSameRowAndPreservesCreationTime() {
        CustomerSummary saved = generateAndCache(); saved.setId(12L);
        LocalDateTime created = saved.getCreatedAt();
        when(models.generate(anyString(), eq("CUSTOMER_SUMMARY"))).thenReturn(new ModelPoolClient.ModelResult(
                "{\"overview\":\"客户已成交\",\"suggestion\":\"确认交付安排\"}", "backup", 33));
        customer.setStatus("DEAL");
        var result = service.summarize(7L, 8L, true);
        assertEquals("客户已成交", result.overview()); assertFalse(result.stale());
        assertEquals(created, saved.getCreatedAt()); assertEquals(12L, saved.getId());
        assertEquals("backup", saved.getModel()); assertTrue(saved.getPrompt().contains("DEAL"));
        verify(summaries).updateById(saved); verify(summaries, times(1)).insert(any(CustomerSummary.class));
    }

    @Test
    void failedOrInvalidRefreshKeepsOldSummaryAndAllowsRetry() {
        CustomerSummary saved = generateAndCache(); String hash = saved.getSourceHash();
        LocalDateTime updated = saved.getUpdatedAt();
        when(models.generate(anyString(), eq("CUSTOMER_SUMMARY")))
                .thenThrow(ApiException.badRequest("模型失败"))
                .thenReturn(new ModelPoolClient.ModelResult("{\"overview\":\"不完整\"}", "bad", 1))
                .thenReturn(new ModelPoolClient.ModelResult(brief, "retry", 2));
        assertThrows(ApiException.class, () -> service.summarize(7L, 8L, true));
        assertThrows(ApiException.class, () -> service.summarize(7L, 8L, true));
        assertEquals(hash, saved.getSourceHash()); assertEquals(updated, saved.getUpdatedAt());
        assertEquals("test-model", saved.getModel());
        verify(summaries, never()).updateById(any(CustomerSummary.class));
        assertEquals(saved.getOverview(), service.summarize(7L, 8L, false).overview());
        assertFalse(service.summarize(7L, 8L, true).stale()); assertEquals("retry", saved.getModel());
    }

    @Test
    void missingOwnerOrInaccessibleCustomerCannotReadCachedSummary() {
        generateAndCache(); clearInvocations(summaries, follows, models);
        when(customers.get(7L, 8L)).thenThrow(ApiException.notFound("客户不存在"));
        assertThrows(ApiException.class, () -> service.summarize(7L, 8L, false));
        assertThrows(ApiException.class, () -> service.summarize(7L, 8L, true));
        when(users.selectOne(any())).thenReturn(null);
        assertThrows(ApiException.class, () -> service.summarize(7L, 8L, false));
        verifyNoInteractions(summaries, follows, models);
    }

    @Test
    void firstLoadWithoutHistoryUsesModelAndPersistsProfileAnalysis() throws Exception {
        customer.setLastFollowTime(null);
        when(follows.selectList(any())).thenReturn(List.of()); when(follows.selectCount(any())).thenReturn(0L);
        String profileBrief = "{\"overview\":\"已填写装修需求，预计金额15万元，尚无沟通反馈\",\"suggestion\":\"了解房屋面积与装修范围后准备预算内方案\"}";
        when(models.generate(anyString(), eq("CUSTOMER_SUMMARY")))
                .thenReturn(new ModelPoolClient.ModelResult(profileBrief, "profile-model", 32));
        var result = service.summarize(7L, 8L, false);
        assertFalse(result.hasContactHistory()); assertEquals(0, result.followCount()); assertEquals(10, result.daysWithoutContact());
        assertTrue(result.aiGenerated()); assertNotNull(result.updatedAt());
        assertEquals(0, result.recordsUsed());
        assertEquals("了解房屋面积与装修范围后准备预算内方案", result.suggestion());
        ArgumentCaptor<CustomerSummary> captured = ArgumentCaptor.forClass(CustomerSummary.class);
        verify(summaries).insert(captured.capture());
        var saved = captured.getValue();
        assertEquals("profile-model", saved.getModel());
        var facts = json.readTree(saved.getPrompt().substring(saved.getPrompt().indexOf('{'))).get("customerFacts");
        assertEquals("装修", facts.get("need").asText()); assertEquals(150000, facts.get("budget").asInt());
        assertTrue(facts.get("history").isEmpty()); assertFalse(facts.get("hasContactHistory").asBoolean());
        verify(models).generate(anyString(), eq("CUSTOMER_SUMMARY"));
        verify(summaries, never()).updateById(any(CustomerSummary.class));
    }

    @Test
    void noHistoryModelFailureDoesNotReturnOrSaveFixedAdvice() {
        when(follows.selectList(any())).thenReturn(List.of()); when(follows.selectCount(any())).thenReturn(0L);
        when(models.generate(anyString(), eq("CUSTOMER_SUMMARY")))
                .thenThrow(ApiException.badRequest("模型失败"))
                .thenReturn(new ModelPoolClient.ModelResult("{\"overview\":\"缺少建议\"}", "bad", 1));
        assertThrows(ApiException.class, () -> service.summarize(7L, 8L, false));
        assertThrows(ApiException.class, () -> service.summarize(7L, 8L, true));
        verify(summaries, never()).insert(any(CustomerSummary.class));
        verify(summaries, never()).updateById(any(CustomerSummary.class));
    }

    @Test
    void sparseNewCustomerRegeneratesOldSummaryUsingModelWithSeparateSellerFacts() throws Exception {
        User user = new User(); user.setId(7L); user.setIndustry("汽车销售");
        when(users.selectOne(any())).thenReturn(user);
        preferences.setBusinessIntro("本人从事汽车销售");
        customer.setNeed(null); customer.setBudget(null); customer.setStage("新客户"); customer.setLastFollowTime(null);
        when(follows.selectList(any())).thenReturn(List.of()); when(follows.selectCount(any())).thenReturn(0L);
        CustomerSummary old = new CustomerSummary(); old.setOverview("王先生从事汽车销售，关注价格");
        old.setSuggestion("推进汽车销售成交"); when(summaries.selectOne(any())).thenReturn(old);
        when(models.generate(anyString(), eq("CUSTOMER_SUMMARY"))).thenReturn(new ModelPoolClient.ModelResult(
                "{\"overview\":\"新客户，具体需求和预算尚未记录\",\"suggestion\":\"首次沟通了解所需服务及计划时间\"}", "profile-model", 32));
        var result = service.summarize(7L, 8L, false);
        assertTrue(result.aiGenerated()); assertFalse(result.stale());
        assertEquals("首次沟通了解所需服务及计划时间", result.suggestion());
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(models).generate(prompt.capture(), eq("CUSTOMER_SUMMARY"));
        var snapshot = json.readTree(prompt.getValue().substring(prompt.getValue().indexOf('{')));
        assertTrue(snapshot.get("customerFacts").get("need").isNull());
        assertTrue(snapshot.get("customerFacts").get("budget").isNull());
        assertTrue(snapshot.get("customerFacts").get("history").isEmpty());
        assertEquals("汽车销售", snapshot.get("sellerContext").get("businessIndustry").asText());
        assertTrue(prompt.getValue().contains("不是客户的职业或行业"));
        verify(summaries).updateById(old);
    }

    @Test
    void sellerOccupationIsSeparatedFromCustomerFactsEvenWhenHistoryIsAvailable() throws Exception {
        User user = new User(); user.setId(7L); user.setIndustry("汽车销售");
        when(users.selectOne(any())).thenReturn(user);
        preferences.setBusinessIntro("本人经营汽车门店");
        service.summarize(7L, 8L, false);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(models).generate(prompt.capture(), eq("CUSTOMER_SUMMARY"));
        var snapshot = json.readTree(prompt.getValue().substring(prompt.getValue().indexOf('{')));
        assertEquals("汽车销售", snapshot.get("sellerContext").get("businessIndustry").asText());
        assertEquals("装修", snapshot.get("customerFacts").get("need").asText());
        assertFalse(snapshot.get("customerFacts").has("industry"));
        assertFalse(snapshot.get("customerFacts").has("occupation"));
        assertFalse(snapshot.has("industry"));
        assertTrue(prompt.getValue().contains("不是客户的职业或行业"));
        assertTrue(prompt.getValue().contains("客户概况只能依据customerFacts"));
    }

    @Test
    void oldPromptSchemaIsRegeneratedWithoutReturningTheWrongCachedSummary() {
        CustomerSummary old = new CustomerSummary(); old.setId(12L);
        old.setOverview("客户从事汽车销售"); old.setSuggestion("催成交");
        old.setPrompt("客户资料：{\"industry\":\"汽车销售\"}");
        old.setCreatedAt(LocalDateTime.now(AiQuota.ZONE).minusDays(1));
        when(summaries.selectOne(any())).thenReturn(old);
        var result = service.summarize(7L, 8L, false);
        assertNotEquals("客户从事汽车销售", result.overview());
        assertTrue(result.aiGenerated()); assertFalse(result.stale());
        verify(models).generate(anyString(), eq("CUSTOMER_SUMMARY"));
        verify(summaries).updateById(old);
        assertTrue(old.getPrompt().contains("\"schemaVersion\":2"));
        assertTrue(old.getPrompt().contains("\"sellerContext\""));
    }

    @Test
    void missingLastContactFallsBackToLatestRecordAndReadsOnlyThirtyRecords() {
        customer.setLastFollowTime(null);
        when(follows.selectList(any())).thenReturn(IntStream.range(0, 30).mapToObj(i -> follow(30 - i, "沟通")).toList());
        when(follows.selectCount(any())).thenReturn(80L);
        var result = service.summarize(7L, 8L, false);
        assertTrue(result.hasContactHistory()); assertEquals(4, result.daysWithoutContact());
        assertEquals(80, result.followCount()); assertEquals(30, result.recordsUsed());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void queriesRequireCurrentOwnerCustomerAndHistoryLimit() {
        service.summarize(7L, 8L, false);
        ArgumentCaptor<LambdaQueryWrapper<FollowRecord>> history = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(follows).selectList(history.capture());
        assertScoped(history.getValue());
        assertTrue(history.getValue().getSqlSegment().contains("created_at DESC,id DESC"));
        assertTrue(history.getValue().getSqlSegment().endsWith("LIMIT 30"));
        ArgumentCaptor<LambdaQueryWrapper<FollowRecord>> count = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(follows).selectCount(count.capture()); assertScoped(count.getValue());
        ArgumentCaptor<LambdaQueryWrapper<CustomerSummary>> cache = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(summaries).selectOne(cache.capture()); assertScoped(cache.getValue());
        ArgumentCaptor<LambdaQueryWrapper<User>> owner = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(users).selectOne(owner.capture());
        assertTrue(owner.getValue().getSqlSegment().endsWith("FOR UPDATE"));
        assertTrue(owner.getValue().getParamNameValuePairs().containsValue(7L));
    }

    private void assertScoped(LambdaQueryWrapper<?> query) {
        assertTrue(query.getSqlSegment().contains("user_id ="));
        assertTrue(query.getSqlSegment().contains("customer_id ="));
        assertTrue(query.getParamNameValuePairs().containsValue(7L));
        assertTrue(query.getParamNameValuePairs().containsValue(8L));
    }

    private CustomerSummary generateAndCache() {
        service.summarize(7L, 8L, false);
        ArgumentCaptor<CustomerSummary> captured = ArgumentCaptor.forClass(CustomerSummary.class);
        verify(summaries).insert(captured.capture());
        when(summaries.selectOne(any())).thenReturn(captured.getValue());
        return captured.getValue();
    }

    private FollowRecord follow(long id, String content) {
        FollowRecord record = new FollowRecord(); record.setId(id); record.setUserId(7L); record.setCustomerId(8L);
        record.setContent(content); record.setFollowType("微信"); record.setCustomerFeedback("考虑中");
        record.setCreatedAt(LocalDateTime.now(AiQuota.ZONE).minusDays(4).minusMinutes(30 - id).withNano(0));
        return record;
    }
}
