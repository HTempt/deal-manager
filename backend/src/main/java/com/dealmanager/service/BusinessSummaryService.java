package com.dealmanager.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dealmanager.ai.ModelPoolClient;
import com.dealmanager.ai.AiPreferences;
import com.dealmanager.common.ApiException;
import com.dealmanager.common.LogSafety;
import com.dealmanager.domain.BusinessSummary;
import com.dealmanager.domain.User;
import com.dealmanager.mapper.BusinessSummaryMapper;
import com.dealmanager.mapper.UserMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class BusinessSummaryService {
    private static final int ANALYTICS_VERSION = 2;
    private final DataService dataService;
    private final ModelPoolClient modelPoolClient;
    private final ObjectMapper objectMapper;
    private final BusinessSummaryMapper summaryMapper;
    private final UserMapper userMapper;
    private final SettingsService settingsService;
    private final FollowEfficiencyService followEfficiencyService;

    @Transactional
    public Summary summarize(Long userId, YearMonth month, boolean refresh) {
        log.info("business_summary.requested userId={} month={} refresh={}", userId, month, refresh);
        // 用户行锁让多端首次生成也只调用一次模型，并保证重算失败不会覆盖旧结果。
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getId, userId).last("FOR UPDATE"));
        if (user == null) throw ApiException.notFound("用户不存在");
        AiPreferences preferences = AiPreferences.from(settingsService.aiSetting(userId));
        String settingHash = preferences.fingerprint();
        BusinessSummary saved = summaryMapper.selectOne(new LambdaQueryWrapper<BusinessSummary>()
                .eq(BusinessSummary::getUserId, userId).eq(BusinessSummary::getSummaryMonth, month.toString()));
        if (!refresh && saved != null && StringUtils.hasText(saved.getContent())) {
            boolean stale = !Objects.equals(settingHash, saved.getAiSettingHash()) || saved.getPrompt() == null
                    || !saved.getPrompt().contains("\"analyticsVersion\":" + ANALYTICS_VERSION);
            log.info("business_summary.cache_hit userId={} month={} stale={}", userId, month, stale);
            return new Summary(saved.getContent(), true, saved.getUpdatedAt(), stale);
        }
        DataService.Overview current = dataService.overview(userId, month);
        List<DataService.SourceStat> sources = dataService.sources(userId, month);
        DataService.Funnel funnel = dataService.funnel(userId, month);
        FollowEfficiencyService.Efficiency efficiency = followEfficiencyService.efficiency(userId, month);
        if (current.newCustomers() == 0 && current.dealCustomers() == 0 && sources.isEmpty()
                && efficiency.expectedTasks() == 0 && efficiency.followRecords() == 0) {
            log.info("business_summary.skipped userId={} month={} reason=NO_DATA refresh={}", userId, month, refresh);
            if (refresh) throw ApiException.badRequest("本月暂无经营数据，暂时无法重新分析");
            return new Summary("本月暂无经营数据，新增客户或记录成交后即可生成经营复盘。", false, null, false);
        }
        DataService.Overview previous = dataService.overview(userId, month.minusMonths(1));
        // 上月没有新增客户时不提供增长率，避免把从0增长解释为100%。
        Snapshot snapshot = new Snapshot(month.toString(), current.newCustomers(), previous.newCustomers(),
                previous.newCustomers() == 0 ? null : current.customerGrowthPercent(), current.dealAmount(),
                previous.dealAmount(), current.dealCustomers(), sources, preferences, ANALYTICS_VERSION, funnel, efficiency);
        String prompt;
        try {
            prompt = "以下JSON仅包含当前用户的真实汇总数据，金额单位为人民币元。customerGrowthPercent为null表示上月新增为0，增长率不可计算。渠道成交客户跨渠道可能重叠，不要把各渠道人数相加作为总人数。funnel转化率以所选月新增客户为同一批样本，截至当前累计进展；咨询按新增客户计。顶层成交客户和金额按成交日期统计，不可与新增客户相除。followEfficiency的应跟进按所选月计划日期且截至asOf已到期、未取消的任务计，已完成属于同一批任务，含之后补做的任务；完成不等于按时完成。平均跟进次数按当月实际跟进记录数/实际联系客户数计算；成交周期按本月首次成交客户从录入到首次成交的时间计算，单位天。null表示没有样本，不能当成0。请指出可验证的转化或跟进缺口，给出1至2条复盘建议，不能把猜测写成未成交的原因。\n"
                    + objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            log.error("business_summary.snapshot_failed userId={} month={}", userId, month, LogSafety.exception(e));
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "经营数据整理失败，请重试");
        }
        log.info("business_summary.generating userId={} month={} sourceCount={}", userId, month, sources.size());
        ModelPoolClient.ModelResult result = modelPoolClient.generate(prompt, "SUMMARY");
        if (!StringUtils.hasText(result.content())) {
            log.warn("business_summary.invalid_response userId={} month={} model={}", userId, month, LogSafety.text(result.model()));
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI 未返回有效经营复盘，请重试");
        }
        LocalDateTime now = LocalDateTime.now(AiQuota.ZONE);
        BusinessSummary record = saved == null ? new BusinessSummary() : saved;
        record.setUserId(userId);
        record.setSummaryMonth(month.toString());
        record.setContent(result.content().trim());
        record.setPrompt(prompt);
        record.setModel(result.model());
        record.setTokenUsage(result.tokenUsage());
        record.setAiSettingHash(settingHash);
        record.setUpdatedAt(now);
        if (saved == null) {
            record.setCreatedAt(now);
            summaryMapper.insert(record);
        } else {
            summaryMapper.updateById(record);
        }
        log.info("business_summary.saved userId={} month={} summaryId={} model={} tokens={}", userId, month, record.getId(),
                LogSafety.text(result.model()), result.tokenUsage());
        return new Summary(record.getContent(), true, now, false);
    }

    public record Summary(String content, boolean aiGenerated, LocalDateTime updatedAt, boolean stale) {}
    private record Snapshot(String month, long newCustomers, long previousNewCustomers,
                            BigDecimal customerGrowthPercent, BigDecimal dealAmount, BigDecimal previousDealAmount,
                            long dealCustomers, List<DataService.SourceStat> sources, AiPreferences globalAiSettings,
                            int analyticsVersion, DataService.Funnel funnel, FollowEfficiencyService.Efficiency followEfficiency) {}
}
