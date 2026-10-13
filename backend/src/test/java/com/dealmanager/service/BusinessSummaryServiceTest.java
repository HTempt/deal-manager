package com.dealmanager.service;

import com.dealmanager.ai.ModelPoolClient;
import com.dealmanager.ai.AiPreferences;
import com.dealmanager.common.ApiException;
import com.dealmanager.domain.BusinessSummary;
import com.dealmanager.domain.User;
import com.dealmanager.domain.UserAiSetting;
import com.dealmanager.mapper.BusinessSummaryMapper;
import com.dealmanager.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BusinessSummaryServiceTest {
    private final DataService data = mock(DataService.class);
    private final ModelPoolClient models = mock(ModelPoolClient.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final FollowEfficiencyService efficiency = mock(FollowEfficiencyService.class);
    private final BusinessSummaryMapper summaries = mock(BusinessSummaryMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final SettingsService settings = mock(SettingsService.class);
    private UserAiSetting preferences;
    private final YearMonth month = YearMonth.of(2026, 10);
    private BusinessSummaryService service;

    @BeforeEach
    void setUp() {
        service = new BusinessSummaryService(data, models, json, summaries, users, settings, efficiency);
        preferences = new UserAiSetting(); preferences.setReplyStyle("专业正式"); preferences.setReplyLength("详细");
        preferences.setPreferredAddress("您"); preferences.setBusinessIntro("住宅装修");
        preferences.setBusinessAdvantage("报价透明"); preferences.setCommonExpression("为您确认");
        when(settings.aiSetting(anyLong())).thenReturn(preferences);
        User user = new User();
        user.setId(7L);
        when(users.selectOne(any())).thenReturn(user);
        when(data.overview(anyLong(), eq(month))).thenReturn(overview(month, 5, "2000", 2));
        when(data.overview(anyLong(), eq(month.minusMonths(1)))).thenReturn(overview(month.minusMonths(1), 0, "500", 1));
        when(data.sources(anyLong(), eq(month))).thenReturn(List.of(new DataService.SourceStat("微信", 5, 2, new BigDecimal("2000"))));
        when(efficiency.efficiency(anyLong(), any())).thenReturn(new FollowEfficiencyService.Efficiency("2026-10",
                LocalDateTime.of(2026, 10, 10, 10, 0), 86, 72, 14, new BigDecimal("83.7"), 38, 10,
                new BigDecimal("3.8"), 12, new BigDecimal("12.4")));
        when(data.funnel(anyLong(), any())).thenReturn(new DataService.Funnel("2026-10", "同一批新增客户", "截至当前", List.of(),
                0, new BigDecimal("9.5"), new BigDecimal("27.9"), new BigDecimal("34.1")));
        when(models.generate(anyString(), eq("SUMMARY"))).thenReturn(new ModelPoolClient.ModelResult("实际经营小结", "test-model", 30));
    }

    @Test
    void promptContainsActualAggregatesAndNoGrowthRateFromZero() throws Exception {
        assertTrue(service.summarize(7L, month, false).aiGenerated());
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(models).generate(prompt.capture(), eq("SUMMARY"));
        String payload = prompt.getValue().substring(prompt.getValue().indexOf('{'));
        var snapshot = json.readTree(payload);
        assertEquals("2026-10", snapshot.get("month").asText());
        assertEquals(5, snapshot.get("newCustomers").asInt());
        assertEquals(2000, snapshot.get("dealAmount").asInt());
        assertEquals(500, snapshot.get("previousDealAmount").asInt());
        assertEquals(2, snapshot.get("analyticsVersion").asInt());
        assertEquals(34.1, snapshot.get("funnel").get("inquiryToQuotePercent").asDouble());
        assertEquals(83.7, snapshot.get("followEfficiency").get("completionPercent").asDouble());
        assertEquals(14, snapshot.get("followEfficiency").get("pendingTasks").asInt());
        assertTrue(snapshot.get("customerGrowthPercent").isNull());
        assertEquals("微信", snapshot.get("sources").get(0).get("source").asText());
        assertEquals("专业正式", snapshot.get("globalAiSettings").get("replyStyle").asText());
        assertEquals("详细", snapshot.get("globalAiSettings").get("replyLength").asText());
        assertEquals("您", snapshot.get("globalAiSettings").get("preferredAddress").asText());
        assertEquals("住宅装修", snapshot.get("globalAiSettings").get("businessIntro").asText());
        assertEquals("报价透明", snapshot.get("globalAiSettings").get("businessAdvantage").asText());
        assertEquals("为您确认", snapshot.get("globalAiSettings").get("commonExpression").asText());
        verify(data).overview(7L, month);
        verify(data).sources(7L, month);
        verify(data).overview(7L, month.minusMonths(1));
        ArgumentCaptor<BusinessSummary> record = ArgumentCaptor.forClass(BusinessSummary.class);
        verify(summaries).insert(record.capture());
        assertEquals(7L, record.getValue().getUserId());
        assertEquals("2026-10", record.getValue().getSummaryMonth());
        assertEquals("实际经营小结", record.getValue().getContent());
        assertEquals("test-model", record.getValue().getModel());
        assertEquals(30, record.getValue().getTokenUsage());
        assertEquals(prompt.getValue(), record.getValue().getPrompt());
        assertNotNull(record.getValue().getCreatedAt());
        assertNotNull(record.getValue().getUpdatedAt());
        assertEquals(AiPreferences.from(preferences).fingerprint(), record.getValue().getAiSettingHash());
    }

    @Test
    void persistedSummaryIsReadFirstEvenAfterServiceRestartAndDataChanges() {
        BusinessSummary saved = savedSummary();
        when(summaries.selectOne(any())).thenReturn(saved);
        when(data.overview(7L, month)).thenReturn(overview(month, 999, "2000", 2));
        var result = service.summarize(7L, month, false);
        assertEquals(saved.getContent(), result.content());
        assertEquals(saved.getUpdatedAt(), result.updatedAt());
        service = new BusinessSummaryService(data, models, json, summaries, users, settings, efficiency);
        assertEquals(saved.getContent(), service.summarize(7L, month, false).content());
        verifyNoInteractions(data, models);
        verify(summaries, never()).insert(any(BusinessSummary.class));
        verify(summaries, never()).updateById(any(BusinessSummary.class));
    }

    @Test
    void globalPreferencesChangeMarksSavedSummaryStaleUntilManualRefresh() {
        BusinessSummary saved = savedSummary();
        saved.setAiSettingHash(AiPreferences.from(preferences).fingerprint());
        when(summaries.selectOne(any())).thenReturn(saved);
        assertFalse(service.summarize(7L, month, false).stale());
        preferences.setReplyStyle("简洁直接"); preferences.setReplyLength("简短");
        var cached = service.summarize(7L, month, false);
        assertTrue(cached.stale()); assertEquals("已保存的小结", cached.content());
        verifyNoInteractions(models);
        assertFalse(service.summarize(7L, month, true).stale());
        assertEquals(AiPreferences.from(preferences).fingerprint(), saved.getAiSettingHash());
        assertTrue(saved.getPrompt().contains("简洁直接"));
        assertTrue(saved.getPrompt().contains("简短"));
    }

    @Test
    void legacySavedSummaryWithoutSettingsHashIsStaleButStillReadable() {
        when(summaries.selectOne(any())).thenReturn(savedSummary());
        var cached = service.summarize(7L, month, false);
        assertEquals("已保存的小结", cached.content()); assertTrue(cached.stale());
        verifyNoInteractions(models);
    }

    @Test
    void missingSummaryIsGeneratedForEachUserAndMonth() {
        service.summarize(7L, month, false);
        service.summarize(8L, month, false);
        YearMonth next = month.plusMonths(1);
        when(data.overview(7L, next)).thenReturn(overview(next, 5, "2000", 2));
        when(data.sources(7L, next)).thenReturn(List.of());
        service.summarize(7L, next, false);
        verify(models, times(3)).generate(anyString(), eq("SUMMARY"));
        ArgumentCaptor<BusinessSummary> records = ArgumentCaptor.forClass(BusinessSummary.class);
        verify(summaries, times(3)).insert(records.capture());
        assertEquals(7L, records.getAllValues().get(0).getUserId());
        assertEquals(8L, records.getAllValues().get(1).getUserId());
        assertEquals("2026-11", records.getAllValues().get(2).getSummaryMonth());
    }

    @Test
    void noDataDoesNotCallModel() {
        when(data.overview(7L, month)).thenReturn(overview(month, 0, "0", 0));
        when(data.sources(7L, month)).thenReturn(List.of());
        when(efficiency.efficiency(7L, month)).thenReturn(new FollowEfficiencyService.Efficiency("2026-10",
                LocalDateTime.now(AiQuota.ZONE), 0, 0, 0, null, 0, 0, null, 0, null));
        assertFalse(service.summarize(7L, month, false).aiGenerated());
        verifyNoInteractions(models);
        verify(summaries, never()).insert(any(BusinessSummary.class));
    }

    @Test
    void existingCustomerFollowActivityIsEnoughToGenerateAReviewWithoutNewSales() {
        when(data.overview(7L, month)).thenReturn(overview(month, 0, "0", 0));
        when(data.sources(7L, month)).thenReturn(List.of());
        assertTrue(service.summarize(7L, month, false).aiGenerated());
        verify(models).generate(anyString(), eq("SUMMARY"));
    }

    @Test
    void preEfficiencySummaryIsMarkedForManualReviewWithoutCallingTheModel() {
        BusinessSummary saved = savedSummary(); saved.setAiSettingHash(AiPreferences.from(preferences).fingerprint());
        saved.setPrompt("旧经营数据"); when(summaries.selectOne(any())).thenReturn(saved);
        assertTrue(service.summarize(7L, month, false).stale());
        verifyNoInteractions(data, efficiency, models);
    }

    @Test
    void failureAndEmptyResponseAreNotPersisted() {
        when(models.generate(anyString(), eq("SUMMARY")))
                .thenThrow(new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "模型错误"))
                .thenReturn(new ModelPoolClient.ModelResult(" ", "test-model", 1))
                .thenReturn(new ModelPoolClient.ModelResult("重试成功", "test-model", 2));
        assertThrows(ApiException.class, () -> service.summarize(7L, month, false));
        assertThrows(ApiException.class, () -> service.summarize(7L, month, false));
        verify(summaries, never()).insert(any(BusinessSummary.class));
        assertEquals("重试成功", service.summarize(7L, month, false).content());
        verify(models, times(3)).generate(anyString(), eq("SUMMARY"));
        verify(summaries, times(1)).insert(any(BusinessSummary.class));
    }

    @Test
    void manualRefreshUpdatesExistingRowAndPreservesCreationTime() {
        BusinessSummary saved = savedSummary();
        LocalDateTime created = saved.getCreatedAt();
        when(summaries.selectOne(any())).thenReturn(saved);
        assertEquals("实际经营小结", service.summarize(7L, month, true).content());
        verify(models).generate(anyString(), eq("SUMMARY"));
        ArgumentCaptor<BusinessSummary> record = ArgumentCaptor.forClass(BusinessSummary.class);
        verify(summaries).updateById(record.capture());
        assertEquals(10L, record.getValue().getId());
        assertEquals(created, record.getValue().getCreatedAt());
        assertEquals("实际经营小结", record.getValue().getContent());
        verify(summaries, never()).insert(any(BusinessSummary.class));
    }

    @Test
    void failedRefreshKeepsOldSummaryAndCanBeRetried() {
        BusinessSummary saved = savedSummary();
        when(summaries.selectOne(any())).thenReturn(saved);
        when(models.generate(anyString(), eq("SUMMARY")))
                .thenThrow(new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "模型错误"))
                .thenReturn(new ModelPoolClient.ModelResult("重算成功", "test-model", 2));
        assertThrows(ApiException.class, () -> service.summarize(7L, month, true));
        assertEquals("已保存的小结", saved.getContent());
        verify(summaries, never()).updateById(any(BusinessSummary.class));
        assertEquals("已保存的小结", service.summarize(7L, month, false).content());
        assertEquals("重算成功", service.summarize(7L, month, true).content());
        verify(summaries).updateById(saved);
    }

    @Test
    void refreshWithoutBusinessDataDoesNotOverwriteSavedResult() {
        BusinessSummary saved = savedSummary();
        when(summaries.selectOne(any())).thenReturn(saved);
        when(data.overview(7L, month)).thenReturn(overview(month, 0, "0", 0));
        when(data.sources(7L, month)).thenReturn(List.of());
        when(efficiency.efficiency(7L, month)).thenReturn(new FollowEfficiencyService.Efficiency("2026-10",
                LocalDateTime.now(AiQuota.ZONE), 0, 0, 0, null, 0, 0, null, 0, null));
        assertThrows(ApiException.class, () -> service.summarize(7L, month, true));
        assertEquals("已保存的小结", saved.getContent());
        verifyNoInteractions(models);
        verify(summaries, never()).updateById(any(BusinessSummary.class));
    }

    @Test
    void missingUserCannotReadOrGenerateSummary() {
        when(users.selectOne(any())).thenReturn(null);
        assertThrows(ApiException.class, () -> service.summarize(7L, month, false));
        verifyNoInteractions(summaries, data, models);
    }

    private BusinessSummary savedSummary() {
        BusinessSummary saved = new BusinessSummary();
        saved.setId(10L);
        saved.setUserId(7L);
        saved.setSummaryMonth(month.toString());
        saved.setContent("已保存的小结");
        saved.setPrompt("{\"analyticsVersion\":2}");
        saved.setCreatedAt(LocalDateTime.of(2026, 10, 1, 9, 0));
        saved.setUpdatedAt(LocalDateTime.of(2026, 10, 7, 9, 0));
        return saved;
    }

    private DataService.Overview overview(YearMonth month, long customers, String amount, long deals) {
        return new DataService.Overview(month.toString(), customers, BigDecimal.valueOf(100), new BigDecimal(amount), deals);
    }
}
