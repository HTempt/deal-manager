package com.dealmanager.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dealmanager.ai.ModelPoolClient;
import com.dealmanager.common.ApiException;
import com.dealmanager.domain.Customer;
import com.dealmanager.domain.DailyAdvice;
import com.dealmanager.domain.FollowRecord;
import com.dealmanager.domain.FollowTask;
import com.dealmanager.domain.User;
import com.dealmanager.domain.UserAiSetting;
import com.dealmanager.mapper.DailyAdviceMapper;
import com.dealmanager.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DailyAdviceServiceTest {
    private final HomeService homeService = mock(HomeService.class);
    private final UserMapper users = mock(UserMapper.class);
    private final DailyAdviceMapper adviceMapper = mock(DailyAdviceMapper.class);
    private final ModelPoolClient models = mock(ModelPoolClient.class);
    private final ObjectMapper json = new ObjectMapper();
    private final SettingsService settings = mock(SettingsService.class);
    private List<PriorityCandidates.Candidate> candidates = new ArrayList<>();
    private DailyAdviceService service;

    @BeforeEach
    void setUp() {
        for (Class<?> type : List.of(User.class, DailyAdvice.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), type);
        }
        service = new DailyAdviceService(homeService, users, adviceMapper, models, json, settings);
        User user = new User(); user.setId(7L); user.setIndustry("装修");
        when(users.selectOne(any())).thenReturn(user);
        when(settings.aiSetting(7L)).thenReturn(new UserAiSetting());
        candidates = new ArrayList<>(List.of(
                candidate(1L, "王先生", "报价中", "38000", 80, "没有回应", 3, LocalDateTime.now(AiQuota.ZONE).minusDays(3)),
                candidate(2L, "李女士", "需求沟通", "20000", 75, "有兴趣", 1, null),
                candidate(3L, "张先生", "报价中", "26000", 65, "觉得报价有点高", 5, LocalDateTime.now(AiQuota.ZONE).minusDays(5)),
                candidate(4L, "赵先生", "方案中", "15000", 60, null, 18, null),
                candidate(5L, "孙女士", "谈判中", "50000", 90, "准备成交", 2, null),
                candidate(6L, "周先生", "新客户", "8000", 40, null, 1, null)));
        when(homeService.candidatePool(eq(7L), anyInt())).thenReturn(candidates);
    }

    @Test
    void firstGenerationPersistsPlanAndResolvesLiveCustomerFacts() throws Exception {
        when(models.generate(anyString(), eq("DAILY_ADVICE")))
                .thenReturn(new ModelPoolClient.ModelResult(plan(candidates, 5), "test-model", 88));
        DailyAdviceService.Daily result = service.daily(7L, false);

        assertTrue(result.aiGenerated());
        assertFalse(result.stale());
        assertEquals(6, result.candidateCount());
        assertEquals(5, result.items().size());
        assertEquals("王先生", result.items().get(0).customerName());
        assertEquals("S", result.items().get(0).priority());
        assertEquals("报价中", result.items().get(0).stage());
        assertEquals(new BigDecimal("38000"), result.items().get(0).budget());
        assertEquals(3, result.items().get(0).daysWithoutContact());
        assertEquals(3, result.items().get(0).overdueDays());
        assertEquals("A", result.items().get(1).priority());

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(models).generate(prompt.capture(), eq("DAILY_ADVICE"));
        assertTrue(prompt.getValue().contains("恰好选出 5 位"));
        assertTrue(prompt.getValue().contains("\"customerId\":1"));
        assertTrue(prompt.getValue().contains("觉得报价有点高"));

        ArgumentCaptor<DailyAdvice> saved = ArgumentCaptor.forClass(DailyAdvice.class);
        verify(adviceMapper).insert(saved.capture());
        DailyAdvice record = saved.getValue();
        assertEquals(7L, record.getUserId());
        assertEquals(LocalDate.now(AiQuota.ZONE), record.getAdviceDate());
        assertEquals(result.overview(), record.getOverview());
        assertEquals(6, record.getCandidateCount());
        assertEquals(64, record.getSourceHash().length());
        assertEquals("test-model", record.getModel());
        assertEquals(88, record.getTokenUsage());
        assertEquals(record.getCreatedAt(), record.getUpdatedAt());
        assertEquals(5, json.readTree(record.getItems()).size());
        assertTrue(record.getPrompt().contains("\"schemaVersion\":1"));
    }

    @Test
    void unchangedFactsReuseSavedAdviceWithoutCallingTheModelAgain() {
        when(models.generate(anyString(), eq("DAILY_ADVICE")))
                .thenReturn(new ModelPoolClient.ModelResult(plan(candidates, 5), "test-model", 88));
        service.daily(7L, false);
        DailyAdvice saved = captureInsert();
        when(adviceMapper.selectOne(any())).thenReturn(saved);

        DailyAdviceService.Daily cached = service.daily(7L, false);
        verify(models, times(1)).generate(anyString(), eq("DAILY_ADVICE"));
        assertFalse(cached.stale());
        assertEquals(5, cached.items().size());
        assertEquals(saved.getUpdatedAt(), cached.updatedAt());
        verify(adviceMapper, never()).updateById(any(DailyAdvice.class));
    }

    @Test
    void changedCandidateFactsKeepTheSavedAdviceAndFlagItAsStale() {
        when(models.generate(anyString(), eq("DAILY_ADVICE")))
                .thenReturn(new ModelPoolClient.ModelResult(plan(candidates, 5), "test-model", 88));
        service.daily(7L, false);
        DailyAdvice saved = captureInsert();
        when(adviceMapper.selectOne(any())).thenReturn(saved);

        // 新客户进入候选池后，当天已保存的建议仍然可读，但需要提示重新生成。
        candidates.add(candidate(9L, "钱先生", "需求沟通", "30000", 85, "有兴趣", 0, null));
        DailyAdviceService.Daily stale = service.daily(7L, false);
        verify(models, times(1)).generate(anyString(), eq("DAILY_ADVICE"));
        assertTrue(stale.stale());
        assertEquals(5, stale.items().size());
        assertEquals(7, stale.candidateCount());
        verify(adviceMapper, never()).updateById(any(DailyAdvice.class));
    }

    @Test
    void refreshRegeneratesAndOverwritesTheSameDayRow() {
        when(models.generate(anyString(), eq("DAILY_ADVICE")))
                .thenReturn(new ModelPoolClient.ModelResult(plan(candidates, 5), "test-model", 88));
        service.daily(7L, false);
        DailyAdvice saved = captureInsert();
        when(adviceMapper.selectOne(any())).thenReturn(saved);

        DailyAdviceService.Daily refreshed = service.daily(7L, true);
        verify(models, times(2)).generate(anyString(), eq("DAILY_ADVICE"));
        // 重新生成只覆盖当天那一行，不新增记录。
        verify(adviceMapper, times(1)).insert(any(DailyAdvice.class));
        ArgumentCaptor<DailyAdvice> updated = ArgumentCaptor.forClass(DailyAdvice.class);
        verify(adviceMapper, times(1)).updateById(updated.capture());
        assertEquals(saved.getId(), updated.getValue().getId());
        assertEquals(7L, updated.getValue().getUserId());
        assertFalse(refreshed.stale());
        assertTrue(refreshed.aiGenerated());
    }

    @Test
    void invalidModelPlanIsRejectedWithoutSavingOrReplacingTheOldAdvice() {
        when(models.generate(anyString(), eq("DAILY_ADVICE"))).thenReturn(new ModelPoolClient.ModelResult(
                "{\"overview\":\"建议\",\"items\":[{\"customerId\":99,\"priority\":\"S\",\"advice\":\"联系\"}]}", "m", 10));
        ApiException error = assertThrows(ApiException.class, () -> service.daily(7L, false));
        assertEquals(502, error.getStatus().value());
        assertTrue(error.getMessage().contains("今日建议"));
        verify(adviceMapper, never()).insert(any(DailyAdvice.class));
        verify(adviceMapper, never()).updateById(any(DailyAdvice.class));
    }

    @Test
    void savedPlanThatNoLongerMatchesAnyCandidateIsRegenerated() {
        DailyAdvice saved = new DailyAdvice();
        saved.setId(3L); saved.setUserId(7L); saved.setAdviceDate(LocalDate.now(AiQuota.ZONE));
        saved.setOverview("旧建议"); saved.setItems("[{\"customerId\":99,\"priority\":\"S\",\"advice\":\"联系旧客户\"}]");
        saved.setSourceHash("hash"); saved.setPrompt("候选客户\n{\"schemaVersion\":1}");
        saved.setModel("m"); saved.setTokenUsage(1); saved.setUpdatedAt(LocalDateTime.now(AiQuota.ZONE));
        when(adviceMapper.selectOne(any())).thenReturn(saved);
        when(models.generate(anyString(), eq("DAILY_ADVICE")))
                .thenReturn(new ModelPoolClient.ModelResult(plan(candidates, 5), "m", 20));

        DailyAdviceService.Daily result = service.daily(7L, false);
        verify(models).generate(anyString(), eq("DAILY_ADVICE"));
        verify(adviceMapper).updateById(any(DailyAdvice.class));
        assertEquals(5, result.items().size());
        assertEquals("王先生", result.items().get(0).customerName());
    }

    @Test
    void oldSchemaPromptForcesRegeneration() {
        when(models.generate(anyString(), eq("DAILY_ADVICE")))
                .thenReturn(new ModelPoolClient.ModelResult(plan(candidates, 5), "m", 20));
        service.daily(7L, false);
        DailyAdvice saved = captureInsert();
        saved.setPrompt("旧输入结构\n{\"schemaVersion\":0}");
        when(adviceMapper.selectOne(any())).thenReturn(saved);

        service.daily(7L, false);
        verify(models, times(2)).generate(anyString(), eq("DAILY_ADVICE"));
    }

    @Test
    void fewCandidatesAskForExactlyThatNumberOfItems() {
        candidates = new ArrayList<>(List.of(candidate(1L, "王先生", "报价中", "38000", 80, "没有回应", 3, null),
                candidate(2L, "李女士", "需求沟通", "20000", 75, "有兴趣", 1, null)));
        when(homeService.candidatePool(eq(7L), anyInt())).thenReturn(candidates);
        when(models.generate(anyString(), eq("DAILY_ADVICE")))
                .thenReturn(new ModelPoolClient.ModelResult(plan(candidates, 2), "m", 20));

        DailyAdviceService.Daily result = service.daily(7L, false);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(models).generate(prompt.capture(), eq("DAILY_ADVICE"));
        assertTrue(prompt.getValue().contains("恰好选出 2 位"));
        assertEquals(2, result.items().size());
    }

    @Test
    void noCandidateSkipsTheModelAndRefreshReportsTheFact() {
        when(homeService.candidatePool(eq(7L), anyInt())).thenReturn(List.of());
        DailyAdviceService.Daily empty = service.daily(7L, false);
        assertTrue(empty.items().isEmpty());
        assertFalse(empty.aiGenerated());
        assertEquals(0, empty.candidateCount());
        assertTrue(empty.overview().contains("暂无需要重点跟进"));
        verifyNoInteractions(models);
        verify(adviceMapper, never()).insert(any(DailyAdvice.class));

        ApiException error = assertThrows(ApiException.class, () -> service.daily(7L, true));
        assertEquals(400, error.getStatus().value());
    }

    private DailyAdvice captureInsert() {
        ArgumentCaptor<DailyAdvice> saved = ArgumentCaptor.forClass(DailyAdvice.class);
        verify(adviceMapper).insert(saved.capture());
        return saved.getValue();
    }

    private static String plan(List<PriorityCandidates.Candidate> candidates, int count) {
        StringBuilder text = new StringBuilder("{\"overview\":\"先联系报价后未回复的客户，再跟进主动咨询机会\",\"items\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) text.append(',');
            text.append("{\"customerId\":").append(candidates.get(i).customer().getId())
                    .append(",\"priority\":\"").append(i == 0 ? "S" : "A")
                    .append("\",\"advice\":\"建议今天下午电话联系\"}");
        }
        return text.append("]}").toString();
    }

    private static PriorityCandidates.Candidate candidate(long id, String name, String stage, String budget, int score,
                                                         String feedback, long daysWithoutContact, LocalDateTime planTime) {
        LocalDateTime now = LocalDateTime.now(AiQuota.ZONE);
        Customer customer = new Customer();
        customer.setId(id); customer.setUserId(7L); customer.setName(name); customer.setStage(stage);
        customer.setStatus("ACTIVE"); customer.setIntentionScore(score);
        customer.setBudget(budget == null ? null : new BigDecimal(budget));
        customer.setCreatedAt(now.minusDays(daysWithoutContact + 2));
        customer.setLastFollowTime(daysWithoutContact > 0 ? now.minusDays(daysWithoutContact) : null);
        customer.setNextFollowTime(planTime);
        customer.setTags(List.of("重点"));
        FollowTask task = null;
        if (planTime != null) {
            task = new FollowTask(); task.setId(id); task.setUserId(7L); task.setCustomerId(id);
            task.setStatus("PENDING"); task.setPlanTime(planTime);
        }
        FollowRecord latest = null;
        if (feedback != null) {
            latest = new FollowRecord(); latest.setCustomerId(id); latest.setContent("沟通记录");
            latest.setCustomerFeedback(feedback); latest.setCreatedAt(now.minusDays(3));
        }
        CustomerPriority.Assessment assessment = new CustomerPriority.Assessment(90 - id, "高意向 + ¥" + budget, daysWithoutContact, true);
        return new PriorityCandidates.Candidate(customer, task, latest, assessment);
    }
}