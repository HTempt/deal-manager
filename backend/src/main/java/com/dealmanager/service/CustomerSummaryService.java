package com.dealmanager.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dealmanager.ai.CustomerBrief;
import com.dealmanager.ai.AiPreferences;
import com.dealmanager.ai.ModelPoolClient;
import com.dealmanager.common.ApiException;
import com.dealmanager.common.LogSafety;
import com.dealmanager.domain.Customer;
import com.dealmanager.domain.CustomerSummary;
import com.dealmanager.domain.FollowRecord;
import com.dealmanager.domain.User;
import com.dealmanager.mapper.CustomerSummaryMapper;
import com.dealmanager.mapper.FollowRecordMapper;
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
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class CustomerSummaryService {
    private static final int HISTORY_LIMIT = 30;
    private static final int SUMMARY_SCHEMA_VERSION = 2;
    private final CustomerService customerService;
    private final UserMapper userMapper;
    private final FollowRecordMapper followMapper;
    private final CustomerSummaryMapper summaryMapper;
    private final ModelPoolClient models;
    private final ObjectMapper json;
    private final SettingsService settingsService;

    @Transactional
    public Summary summarize(Long userId, Long customerId, boolean refresh) {
        log.info("customer_summary.requested userId={} customerId={} refresh={}", userId, customerId, refresh);
        // Serialize first generation and refreshes for one owner, as with monthly summaries.
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getId, userId).last("FOR UPDATE"));
        if (user == null) throw ApiException.notFound("用户不存在");
        Customer customer = customerService.get(userId, customerId);
        List<FollowRecord> records = followMapper.selectList(new LambdaQueryWrapper<FollowRecord>()
                .eq(FollowRecord::getUserId, userId).eq(FollowRecord::getCustomerId, customerId)
                .orderByDesc(FollowRecord::getCreatedAt).orderByDesc(FollowRecord::getId).last("LIMIT " + HISTORY_LIMIT));
        long followCount = followMapper.selectCount(new LambdaQueryWrapper<FollowRecord>()
                .eq(FollowRecord::getUserId, userId).eq(FollowRecord::getCustomerId, customerId));
        LocalDateTime now = LocalDateTime.now(AiQuota.ZONE);
        LocalDateTime last = customer.getLastFollowTime();
        if (last == null && !records.isEmpty()) last = records.get(0).getCreatedAt();
        boolean hasContactHistory = followCount > 0 || last != null;
        LocalDateTime reference = last == null ? customer.getCreatedAt() : last;
        long days = reference == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(reference, now));
        CustomerSummary saved = summaryMapper.selectOne(new LambdaQueryWrapper<CustomerSummary>()
                .eq(CustomerSummary::getUserId, userId).eq(CustomerSummary::getCustomerId, customerId));
        CustomerFacts facts = new CustomerFacts(customer.getName(),
                customer.getNeed(), customer.getBudget(), customer.getStage(), customer.getStatus(),
                customer.getIntentionScore(), customer.getTags()==null?List.of():customer.getTags(), followCount, days, hasContactHistory, time(last),
                time(customer.getNextFollowTime()), customer.getDealAmount(), time(customer.getDealTime()),
                records.stream().map(r -> new History(r.getId(), time(r.getCreatedAt()), r.getFollowType(),
                        r.getContent(), r.getCustomerFeedback(), Boolean.TRUE.equals(r.getActiveInquiry()), Boolean.TRUE.equals(r.getPriceInquiry()))).toList());
        Snapshot snapshot = new Snapshot(SUMMARY_SCHEMA_VERSION, now.toLocalDate().toString(), facts,
                new SellerContext(user.getIndustry(), AiPreferences.from(settingsService.aiSetting(userId))));
        String payload;
        String hash;
        try {
            payload = json.writeValueAsString(snapshot);
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.error("customer_summary.snapshot_failed userId={} customerId={}", userId, customerId, LogSafety.exception(e));
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "客户资料整理失败，请重试");
        }
        if (!refresh && saved != null && hasCurrentSchema(saved)
                && StringUtils.hasText(saved.getOverview()) && StringUtils.hasText(saved.getSuggestion())) {
            boolean stale = !Objects.equals(hash, saved.getSourceHash());
            log.info("customer_summary.cache_hit userId={} customerId={} stale={} recordsUsed={} updatedAt={}",
                    userId, customerId, stale, saved.getRecordsUsed(), saved.getUpdatedAt());
            return result(saved, followCount, days, hasContactHistory, stale);
        }
        log.info("customer_summary.generating userId={} customerId={} refresh={} cached={} historyRecords={} hasNeed={} hasBudget={} tagCount={}",
                userId, customerId, refresh, saved != null, records.size(), StringUtils.hasText(customer.getNeed()),
                customer.getBudget() != null, facts.tags().size());
        String prompt = "以下JSON明确区分客户事实customerFacts与销售者背景sellerContext。"
                + "sellerContext.businessIndustry是登录用户从事的业务行业，不是客户的职业或行业；"
                + "sellerContext.globalAiSettings中的业务介绍和优势也只属于销售者，风格设置控制表达，不补充客户事实。"
                + "客户概况只能依据customerFacts中已填写的资料和最近最多30条跟进记录，最新在前。"
                + "没有明确记录客户职业时，不描述其职业；不得从销售者行业推断客户从事汽车销售、装修等职业，也不得因此认定客户有购车或装修需求。"
                + "预算和成交金额单位为人民币元。"
                + "tags是销售者人工标记，不等同客户亲口反馈；history中的activeInquiry和priceInquiry是该次跟进记录的主动咨询及询价信号。"
                + "history不代表全部历史，followCount是总跟进次数。hasContactHistory为false时daysWithoutContact是创建以来的天数。"
                + "提供客户概况及当前行动建议，缺失资料明确待确认，不编造职业、需求、价格顾虑、家人意见或未记录的反馈。"
                + "没有跟进记录时，仅根据已填写的客户资料生成初步建议，不假定已经沟通过或收到反馈。"
                + "需求和预算已经填写时，不要笼统重复要求确认需求和预算；结合具体需求、预算、阶段和已有跟进计划提出下一步，例如补充尚缺的规格、范围、时间或决策信息。"
                + "动态跟进次数和天数会由界面直接展示，overview不要重复这些数字。\n" + payload;
        ModelPoolClient.ModelResult response = models.generate(prompt, "CUSTOMER_SUMMARY");
        CustomerBrief brief;
        try {
            brief = CustomerBrief.parse(response.content());
        } catch (IllegalArgumentException e) {
            log.warn("customer_summary.invalid_response userId={} customerId={} model={}", userId, customerId, LogSafety.text(response.model()));
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI 未返回有效客户摘要，请重试");
        }
        CustomerSummary record = saved == null ? new CustomerSummary() : saved;
        record.setUserId(userId);
        record.setCustomerId(customerId);
        record.setOverview(brief.overview());
        record.setSuggestion(brief.suggestion());
        record.setSourceHash(hash);
        record.setRecordsUsed(records.size());
        record.setPrompt(prompt);
        record.setModel(response.model());
        record.setTokenUsage(response.tokenUsage());
        record.setUpdatedAt(LocalDateTime.now(AiQuota.ZONE));
        if (saved == null) {
            record.setCreatedAt(record.getUpdatedAt());
            summaryMapper.insert(record);
        } else {
            summaryMapper.updateById(record);
        }
        log.info("customer_summary.saved userId={} customerId={} summaryId={} model={} tokens={} recordsUsed={}",
                userId, customerId, record.getId(), LogSafety.text(response.model()), response.tokenUsage(), records.size());
        return result(record, followCount, days, hasContactHistory, false);
    }

    private static String time(LocalDateTime value) { return value == null ? null : value.toString(); }

    private boolean hasCurrentSchema(CustomerSummary saved) {
        try {
            String prompt = saved.getPrompt();
            if (prompt == null || prompt.indexOf('{') < 0) return false;
            return json.readTree(prompt.substring(prompt.indexOf('{'))).path("schemaVersion").asInt() == SUMMARY_SCHEMA_VERSION;
        } catch (Exception e) { return false; }
    }

    private static Summary result(CustomerSummary record, long count, long days, boolean contacted, boolean stale) {
        return new Summary(record.getOverview(), record.getSuggestion(), count, days, contacted, stale,
                record.getRecordsUsed() == null ? 0 : record.getRecordsUsed(), record.getUpdatedAt(), true);
    }

    public record Summary(String overview, String suggestion, long followCount, long daysWithoutContact,
                          boolean hasContactHistory, boolean stale, int recordsUsed, LocalDateTime updatedAt,
                          boolean aiGenerated) {}
    private record History(Long id, String time, String type, String content, String feedback, boolean activeInquiry, boolean priceInquiry) {}
    private record CustomerFacts(String name, String need, BigDecimal budget,
                            String stage, String status, Integer intentionScore, List<String> tags, long followCount,
                            long daysWithoutContact, boolean hasContactHistory, String lastFollowTime,
                            String nextFollowTime, BigDecimal dealAmount, String dealTime, List<History> history) {}
    private record SellerContext(String businessIndustry, AiPreferences globalAiSettings) {}
    private record Snapshot(int schemaVersion, String date, CustomerFacts customerFacts, SellerContext sellerContext) {}
}
