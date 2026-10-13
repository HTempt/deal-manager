package com.dealmanager.service;

import com.dealmanager.common.ApiException;
import com.dealmanager.domain.Customer;
import com.dealmanager.domain.FollowRecord;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

public final class IntentionScore {
    private IntentionScore() {}

    public static String mode(String requested, String existing) {
        String value = requested == null ? (existing == null ? "AUTO" : existing) : requested;
        if (!List.of("AUTO", "MANUAL").contains(value)) throw ApiException.badRequest("请选择评分方式");
        return value;
    }

    public static int manual(Integer value) {
        if (value == null || value < 0 || value > 100) throw ApiException.badRequest("意向分须为 0～100 的整数");
        return value;
    }

    public static int calculate(Customer customer, List<FollowRecord> history, LocalDateTime now) {
        if ("LOST".equals(customer.getStatus())) return 0;
        if ("DEAL".equals(customer.getStatus())) return 100;
        List<FollowRecord> recent = history.stream()
                .filter(r -> r.getCreatedAt() != null && !r.getCreatedAt().isAfter(now)
                        && !r.getCreatedAt().isBefore(now.minusDays(30)))
                .sorted(Comparator.comparing(FollowRecord::getCreatedAt).reversed()
                        .thenComparing(FollowRecord::getId, Comparator.nullsFirst(Comparator.reverseOrder())))
                .toList();
        List<FollowRecord> replies = recent.stream().filter(IntentionScore::responded).toList();
        long days = replies.isEmpty() ? Long.MAX_VALUE : ChronoUnit.DAYS.between(replies.get(0).getCreatedAt(), now);
        int recency = days <= 7 ? 10 : days <= 14 ? 6 : days <= 30 ? 2 : 0;
        int feedback = recent.isEmpty() ? 0 : switch (text(recent.get(0).getCustomerFeedback())) {
            case "准备成交" -> 20;
            case "有兴趣" -> 12;
            case "考虑中" -> 6;
            default -> 0;
        };
        int inquiry = replies.stream().anyMatch(r -> Boolean.TRUE.equals(r.getActiveInquiry())) ? 10 : 0;
        int price = replies.stream().anyMatch(r -> Boolean.TRUE.equals(r.getPriceInquiry())) ? 10 : 0;
        int budget = customer.getBudget() != null && customer.getBudget().signum() > 0 ? 5 : 0;
        // Only distinct days with a response count; repeated outbound attempts cannot increase the score.
        int frequency = (int) Math.min(5, replies.stream()
                .filter(r -> !r.getCreatedAt().isBefore(now.minusDays(14)))
                .map(r -> r.getCreatedAt().toLocalDate()).distinct().count());
        return Math.min(100, stage(customer.getStage()) + recency + feedback + inquiry + price + budget + frequency);
    }

    private static boolean responded(FollowRecord record) {
        return List.of("有兴趣", "考虑中", "准备成交").contains(text(record.getCustomerFeedback()));
    }

    private static int stage(String value) {
        return switch (text(value)) {
            case "谈判中", "价格沟通", "档期确认", "预约中", "报名考虑", "定金" -> 40;
            case "报价中", "报价", "服务报价", "方案报价" -> 30;
            case "方案中", "方案确认", "套餐沟通", "课程体验", "到店体验", "到店试驾", "带看中", "方案沟通" -> 22;
            case "需求沟通", "需求确认", "需求了解" -> 15;
            // Earlier versions incorrectly stored feedback as the customer stage.
            case "准备成交" -> 40;
            case "有兴趣", "考虑中", "没有回应" -> 10;
            default -> 10;
        };
    }

    private static String text(String value) { return value == null ? "" : value; }
}
