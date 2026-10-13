package com.dealmanager.service;

import com.dealmanager.domain.Customer;
import com.dealmanager.domain.FollowRecord;
import com.dealmanager.domain.FollowTask;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Explainable opportunity ranking. Scores are priorities, not closing probabilities. */
public final class CustomerPriority {
    private static final BigDecimal HIGH_BUDGET = new BigDecimal("20000");
    private static final Set<String> QUOTE_STAGES = Set.of("报价中", "报价", "服务报价", "方案报价");

    private CustomerPriority() {}

    public static Assessment assess(Customer c, FollowTask task, FollowRecord latest, LocalDateTime now) {
        if (!"ACTIVE".equals(c.getStatus())) return null;
        LocalDateTime tomorrow = now.toLocalDate().plusDays(1).atStartOfDay();
        boolean scheduledToday = task != null && task.getPlanTime() != null && task.getPlanTime().isBefore(tomorrow);
        // Honour an explicit later appointment unless there is already a due task.
        if (!scheduledToday && c.getNextFollowTime() != null && !c.getNextFollowTime().isBefore(tomorrow)) return null;

        int intent = c.getIntentionScore() == null ? 0 : Math.max(0, Math.min(100, c.getIntentionScore()));
        BigDecimal budget = c.getBudget() == null ? BigDecimal.ZERO : c.getBudget().max(BigDecimal.ZERO);
        boolean highValue = intent >= 70 || budget.compareTo(HIGH_BUDGET) >= 0;
        boolean hasContactHistory = c.getLastFollowTime() != null || c.getFollowCount() != null && c.getFollowCount() > 0;
        long days = CustomerDormancy.days(c, now);
        long recordDays = latest == null || latest.getCreatedAt() == null ? -1
                : ChronoUnit.DAYS.between(latest.getCreatedAt().toLocalDate(), now.toLocalDate());
        boolean replied = latest != null && Set.of("有兴趣", "考虑中", "准备成交").contains(
                latest.getCustomerFeedback() == null ? "" : latest.getCustomerFeedback());
        boolean inquiry = replied && Boolean.TRUE.equals(latest.getActiveInquiry()) && recordDays >= 0 && recordDays <= 2;
        boolean quoteWaiting = QUOTE_STAGES.contains(c.getStage() == null ? "" : c.getStage())
                && latest != null && "没有回应".equals(latest.getCustomerFeedback()) && recordDays >= 3;
        boolean dormantValue = days >= 15 && highValue;
        boolean unscheduledIntent = c.getNextFollowTime() == null && intent >= 70;
        if (!scheduledToday && !inquiry && !quoteWaiting && !dormantValue && !unscheduledIntent) return null;

        double score = intent * 0.35 + budget.min(new BigDecimal("30000")).doubleValue() / 1000;
        List<String> reasons = new ArrayList<>();
        if (intent >= 70) reasons.add("高意向");
        if (budget.signum() > 0) {
            DecimalFormat amount = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.CHINA));
            reasons.add("¥" + amount.format(budget));
        }
        if (scheduledToday) {
            long overdue = Math.max(0, ChronoUnit.DAYS.between(task.getPlanTime().toLocalDate(), now.toLocalDate()));
            score += overdue > 0 ? 24 + Math.min(overdue, 6) * 4 : 12;
            reasons.add(overdue > 0 ? "逾期" + overdue + "天" : "今天有跟进安排");
        }
        if (inquiry) {
            score += 30;
            reasons.add((recordDays == 0 ? "今天" : recordDays == 1 ? "昨天" : "前天") + "主动咨询");
        }
        if (quoteWaiting) {
            score += 25 + Math.min(recordDays - 3, 5);
            reasons.add("报价中，" + recordDays + "天前记录未回复");
        }
        if (dormantValue) {
            score += 15 + Math.min(days - 15, 10);
            reasons.add(hasContactHistory ? days + "天未联系" : "录入" + days + "天尚未联系");
        }
        if (unscheduledIntent && !scheduledToday) {
            score += 8;
            reasons.add("尚未安排下次跟进");
        }
        return new Assessment(score, String.join(" + ", reasons), days, hasContactHistory);
    }

    public record Assessment(double score, String reasonText, long daysWithoutFollow, boolean hasContactHistory) {}
}
