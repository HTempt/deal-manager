package com.dealmanager.service;

import com.dealmanager.domain.Customer;
import com.dealmanager.domain.FollowRecord;
import com.dealmanager.domain.FollowTask;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 规则可解释的候选客户排序。首页规则列表和 AI 每日建议共用同一份候选池，
 * 保证模型只能看到规则已经认为值得跟进的客户，不会凭空推荐其他人。
 */
public final class PriorityCandidates {
    private PriorityCandidates() {}

    public record Candidate(Customer customer, FollowTask task, FollowRecord latest,
                            CustomerPriority.Assessment assessment) {
        public Long taskId() { return task == null ? null : task.getId(); }
        public LocalDateTime planTime() { return task == null ? null : task.getPlanTime(); }
        public long daysWithoutFollow() { return assessment.daysWithoutFollow(); }
        public String reasonText() { return assessment.reasonText(); }
        public double score() { return assessment.score(); }
    }

    public static List<Candidate> rank(List<Customer> customers, List<FollowTask> activeTasks,
                                      Map<Long, FollowRecord> latestRecords, LocalDateTime now) {
        // 同一客户有多条待办时采用最早的计划时间，避免重复推荐和重复排序。
        Map<Long, FollowTask> earliestTasks = activeTasks.stream().collect(Collectors.toMap(FollowTask::getCustomerId,
                t -> t, (a, b) -> a.getPlanTime().isAfter(b.getPlanTime()) ? b : a));
        return customers.stream().map(c -> {
            FollowTask task = earliestTasks.get(c.getId());
            FollowRecord latest = latestRecords.get(c.getId());
            CustomerPriority.Assessment assessment = CustomerPriority.assess(c, task, latest, now);
            return assessment == null ? null : new Candidate(c, task, latest, assessment);
        }).filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble(Candidate::score).reversed()
                        .thenComparing(c -> c.customer().getIntentionScore(), Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(c -> c.customer().getBudget(), Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Candidate::planTime, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(c -> c.customer().getId()))
                .toList();
    }
}