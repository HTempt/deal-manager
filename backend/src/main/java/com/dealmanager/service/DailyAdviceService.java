package com.dealmanager.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dealmanager.ai.AiPreferences;
import com.dealmanager.ai.DailyPlan;
import com.dealmanager.ai.ModelPoolClient;
import com.dealmanager.common.ApiException;
import com.dealmanager.common.LogSafety;
import com.dealmanager.domain.Customer;
import com.dealmanager.domain.DailyAdvice;
import com.dealmanager.domain.FollowRecord;
import com.dealmanager.domain.User;
import com.dealmanager.mapper.DailyAdviceMapper;
import com.dealmanager.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 每日 AI 工作建议：每天打开首页时，把规则候选池交给模型，生成“今天建议重点跟进哪几位客户”。
 * 同一账号同一天只生成一次，资料变化后标记待更新，由用户主动重新生成；不占用销售话术每日次数。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DailyAdviceService {
    static final int MAX_ITEMS = 5;
    static final int CANDIDATE_LIMIT = 12;
    private static final int SCHEMA_VERSION = 1;

    private final HomeService homeService;
    private final UserMapper userMapper;
    private final DailyAdviceMapper adviceMapper;
    private final ModelPoolClient models;
    private final ObjectMapper json;
    private final SettingsService settingsService;

    @Transactional
    public Daily daily(Long userId, boolean refresh) {
        LocalDateTime now = LocalDateTime.now(AiQuota.ZONE);
        LocalDate date = now.toLocalDate();
        log.info("daily_advice.requested userId={} date={} refresh={}", userId, date, refresh);
        // 用户行锁让多端同时打开首页也只调用一次模型，重算失败不会覆盖当天已保存的建议。
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getId, userId).last("FOR UPDATE"));
        if (user == null) throw ApiException.notFound("用户不存在");
        List<PriorityCandidates.Candidate> candidates = homeService.candidatePool(userId, CANDIDATE_LIMIT);
        if (candidates.isEmpty()) {
            log.info("daily_advice.skipped userId={} date={} reason=NO_CANDIDATE refresh={}", userId, date, refresh);
            if (refresh) throw ApiException.badRequest("今天暂无需要重点跟进的客户，暂时无法重新生成");
            return new Daily(date.toString(), "今天暂无需要重点跟进的客户。补充客户资料、记录跟进或安排下次跟进后，AI 会在这里给出今日建议。",
                    List.of(), 0, null, false, false);
        }
        Map<Long, PriorityCandidates.Candidate> pool = candidates.stream()
                .collect(Collectors.toMap(c -> c.customer().getId(), Function.identity(), (a, b) -> a));
        List<CandidateFacts> facts = candidates.stream().map(c -> facts(c, now)).toList();
        Snapshot snapshot = new Snapshot(SCHEMA_VERSION, date.toString(), MAX_ITEMS, facts,
                AiPreferences.from(settingsService.aiSetting(userId)));
        String payload;
        String hash;
        try {
            payload = json.writeValueAsString(snapshot);
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.error("daily_advice.snapshot_failed userId={} date={}", userId, date, LogSafety.exception(e));
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "客户资料整理失败，请重试");
        }
        DailyAdvice saved = adviceMapper.selectOne(new LambdaQueryWrapper<DailyAdvice>()
                .eq(DailyAdvice::getUserId, userId).eq(DailyAdvice::getAdviceDate, date));
        if (!refresh && saved != null && hasCurrentSchema(saved) && StringUtils.hasText(saved.getOverview())) {
            List<AdviceItem> stored = resolve(storedItems(saved), pool, now);
            if (!stored.isEmpty()) {
                boolean stale = !Objects.equals(hash, saved.getSourceHash());
                log.info("daily_advice.cache_hit userId={} date={} stale={} items={} candidates={} updatedAt={}",
                        userId, date, stale, stored.size(), candidates.size(), saved.getUpdatedAt());
                return new Daily(date.toString(), saved.getOverview(), stored, candidates.size(), saved.getUpdatedAt(), stale, true);
            }
            // 保存的客户已成交、流失或被删除时不再展示过时建议，直接按最新候选重新生成。
            log.warn("daily_advice.cache_unusable userId={} date={} reason=STORED_ITEMS_UNRESOLVED", userId, date);
        }
        int expected = Math.min(MAX_ITEMS, candidates.size());
        Set<Long> allowed = candidates.stream().map(c -> c.customer().getId()).collect(Collectors.toSet());
        String prompt = "以下JSON只包含当前用户在 " + date + " 可以跟进的真实候选客户，金额单位为人民币元。"
                + "globalAiSettings只控制表达风格和业务背景，不补充客户事实。"
                + "candidates[].ruleReason是规则引擎给出的排序依据；daysWithoutContact是完整未联系天数，hasContactHistory为false时表示录入至今尚未联系；"
                + "overdueDays是逾期天数，0表示今天安排或没有待办；latestFeedback是最近一次沟通反馈，没有记录时为null，feedbackDaysAgo是距该次沟通的天数。"
                + "请从candidates中按customerId恰好选出 " + expected + " 位今天最值得重点跟进的客户，"
                + "priority只能取S、A、B（S为今天最优先，A为重要，B为其余值得跟进），"
                + "advice为一句可直接执行的中文建议，最多120字，必须引用候选中的真实事实并给出具体行动和联系时段（上午/下午/晚上），"
                + "例如“报价 ¥38,000，3 天未回复，建议今天下午电话联系”。"
                + "不得编造候选以外的客户、未提供的资料、优惠、承诺或成交概率；客户曾反馈价格偏高或明确拒绝时不要直接催成交，"
                + "应给出降低压力的做法；沉睡客户给出低压力唤醒理由；资料不足时说明需要先确认什么。"
                + "不要推荐已成交、已流失或已删除的客户。\n" + payload;
        log.info("daily_advice.generating userId={} date={} candidates={} expectedItems={} refresh={} cached={}",
                userId, date, candidates.size(), expected, refresh, saved != null);
        ModelPoolClient.ModelResult response = models.generate(prompt, "DAILY_ADVICE");
        DailyPlan plan;
        try {
            plan = DailyPlan.parse(response.content(), allowed, expected);
        } catch (IllegalArgumentException e) {
            log.warn("daily_advice.invalid_response userId={} date={} model={}", userId, date, LogSafety.text(response.model()));
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI 未返回有效今日建议，本次不扣次数，请重试");
        }
        String items;
        try {
            items = json.writeValueAsString(plan.items());
        } catch (Exception e) {
            log.error("daily_advice.items_failed userId={} date={}", userId, date, LogSafety.exception(e));
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "今日建议整理失败，请重试");
        }
        DailyAdvice record = saved == null ? new DailyAdvice() : saved;
        record.setUserId(userId);
        record.setAdviceDate(date);
        record.setOverview(plan.overview());
        record.setItems(items);
        record.setCandidateCount(candidates.size());
        record.setSourceHash(hash);
        record.setPrompt(prompt);
        record.setModel(response.model());
        record.setTokenUsage(response.tokenUsage());
        record.setUpdatedAt(now);
        if (saved == null) {
            record.setCreatedAt(now);
            adviceMapper.insert(record);
        } else {
            adviceMapper.updateById(record);
        }
        log.info("daily_advice.saved userId={} date={} adviceId={} items={} model={} tokens={}",
                userId, date, record.getId(), plan.items().size(), LogSafety.text(response.model()), response.tokenUsage());
        return new Daily(date.toString(), plan.overview(), resolve(plan.items(), pool, now), candidates.size(), now, false, true);
    }

    private List<DailyPlan.Item> storedItems(DailyAdvice saved) {
        try {
            return DailyPlan.parseItems(saved.getItems());
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }

    /** 展示字段按当前客户资料实时解析，只保留仍然有效且仍在候选池中的客户。 */
    private List<AdviceItem> resolve(List<DailyPlan.Item> items, Map<Long, PriorityCandidates.Candidate> pool, LocalDateTime now) {
        List<AdviceItem> result = new ArrayList<>();
        for (DailyPlan.Item item : items) {
            PriorityCandidates.Candidate candidate = pool.get(item.customerId());
            if (candidate == null) continue;
            Customer customer = candidate.customer();
            result.add(new AdviceItem(customer.getId(), customer.getName(), item.priority(), item.advice(),
                    customer.getStage(), customer.getBudget(), customer.getIntentionScore(), candidate.daysWithoutFollow(),
                    candidate.assessment().hasContactHistory(), candidate.planTime(), overdueDays(candidate, now)));
        }
        return List.copyOf(result);
    }

    private CandidateFacts facts(PriorityCandidates.Candidate candidate, LocalDateTime now) {
        Customer customer = candidate.customer();
        FollowRecord latest = candidate.latest();
        LocalDateTime lastFollow = customer.getLastFollowTime();
        Long feedbackDays = latest == null || latest.getCreatedAt() == null ? null
                : Math.max(0, ChronoUnit.DAYS.between(latest.getCreatedAt().toLocalDate(), now.toLocalDate()));
        return new CandidateFacts(customer.getId(), customer.getName(), customer.getStage(), customer.getBudget(),
                customer.getIntentionScore(), candidate.daysWithoutFollow(), candidate.assessment().hasContactHistory(),
                lastFollow == null ? null : lastFollow.toString(),
                customer.getNextFollowTime() == null ? null : customer.getNextFollowTime().toString(),
                overdueDays(candidate, now), candidate.reasonText(),
                latest == null ? null : latest.getCustomerFeedback(),
                latest == null || latest.getCreatedAt() == null ? null : latest.getCreatedAt().toString(),
                latest != null && Boolean.TRUE.equals(latest.getActiveInquiry()),
                latest != null && Boolean.TRUE.equals(latest.getPriceInquiry()), feedbackDays,
                CustomerDormancy.level(customer, now), customer.getTags() == null ? List.of() : customer.getTags());
    }

    private static long overdueDays(PriorityCandidates.Candidate candidate, LocalDateTime now) {
        return candidate.planTime() == null ? 0
                : Math.max(0, ChronoUnit.DAYS.between(candidate.planTime().toLocalDate(), now.toLocalDate()));
    }

    private boolean hasCurrentSchema(DailyAdvice saved) {
        try {
            String prompt = saved.getPrompt();
            if (prompt == null || prompt.indexOf('{') < 0) return false;
            return json.readTree(prompt.substring(prompt.indexOf('{'))).path("schemaVersion").asInt() == SCHEMA_VERSION;
        } catch (Exception e) {
            return false;
        }
    }

    public record Daily(String date, String overview, List<AdviceItem> items, int candidateCount,
                        LocalDateTime updatedAt, boolean stale, boolean aiGenerated) {}
    public record AdviceItem(Long customerId, String customerName, String priority, String advice, String stage,
                             BigDecimal budget, Integer intentionScore, long daysWithoutContact,
                             boolean hasContactHistory, LocalDateTime planTime, long overdueDays) {}
    private record CandidateFacts(Long customerId, String name, String stage, BigDecimal budget, Integer intentionScore,
                                  long daysWithoutContact, boolean hasContactHistory, String lastFollowTime,
                                  String nextFollowTime, long overdueDays, String ruleReason, String latestFeedback,
                                  String latestFeedbackTime, boolean activeInquiry, boolean priceInquiry,
                                  Long feedbackDaysAgo, String dormancyLevel, List<String> tags) {}
    private record Snapshot(int schemaVersion, String date, int maxItems, List<CandidateFacts> candidates,
                            AiPreferences globalAiSettings) {}
}