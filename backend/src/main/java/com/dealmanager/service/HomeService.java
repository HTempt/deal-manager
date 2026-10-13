package com.dealmanager.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dealmanager.domain.Customer;
import com.dealmanager.domain.FollowTask;
import com.dealmanager.domain.FollowRecord;
import com.dealmanager.mapper.CustomerMapper;
import com.dealmanager.mapper.FollowTaskMapper;
import com.dealmanager.mapper.FollowRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class HomeService {
    private final CustomerMapper customerMapper;
    private final FollowTaskMapper taskMapper;
    private final FollowRecordMapper recordMapper;

    public Overview overview(Long userId) {
        LocalDateTime now = LocalDateTime.now(AiQuota.ZONE);
        LocalDateTime dayStart = now.toLocalDate().atStartOfDay();
        Snapshot snapshot = snapshot(userId, now);
        List<Customer> customers = snapshot.customers();
        List<FollowTask> activeTasks = snapshot.activeTasks();
        List<PriorityCustomer> priority = PriorityCandidates.rank(customers, activeTasks, snapshot.latestRecords(), now)
                .stream().limit(10).map(HomeService::toPriorityCustomer).toList();
        long highIntent = customers.stream().filter(c -> c.getIntentionScore() != null && c.getIntentionScore() >= 70).count();
        long overdue = activeTasks.stream().filter(t -> t.getPlanTime().isBefore(dayStart)).map(FollowTask::getCustomerId).distinct().count();
        long todayScheduled = activeTasks.stream().filter(t -> !t.getPlanTime().isBefore(dayStart)).map(FollowTask::getCustomerId).distinct().count();
        long due = activeTasks.stream().filter(t -> !t.getPlanTime().isAfter(now)).map(FollowTask::getCustomerId).distinct().count();
        long unscheduled = customers.stream().filter(c -> c.getNextFollowTime() == null).count();
        long dormant = customers.stream().filter(c -> CustomerDormancy.days(c, now) >= 15).count();
        return new Overview(customers.size(), due, highIntent, overdue, unscheduled, todayScheduled, dormant, priority);
    }

    /** 规则候选池（按价值分降序，已按客户去重）。AI 每日建议只在这些客户里挑选。 */
    public List<PriorityCandidates.Candidate> candidatePool(Long userId, int limit) {
        LocalDateTime now = LocalDateTime.now(AiQuota.ZONE);
        Snapshot snapshot = snapshot(userId, now);
        List<PriorityCandidates.Candidate> ranked = PriorityCandidates.rank(snapshot.customers(), snapshot.activeTasks(),
                snapshot.latestRecords(), now);
        return limit <= 0 || ranked.size() <= limit ? ranked : ranked.subList(0, limit);
    }

    private Snapshot snapshot(Long userId, LocalDateTime now) {
        LocalDateTime dayStart = now.toLocalDate().atStartOfDay();
        List<FollowTask> dueTasks = taskMapper.selectList(new LambdaQueryWrapper<FollowTask>()
                .eq(FollowTask::getUserId, userId).eq(FollowTask::getStatus, "PENDING")
                .lt(FollowTask::getPlanTime, dayStart.plusDays(1)));
        List<Customer> customers = customerMapper.selectList(new LambdaQueryWrapper<Customer>()
                .eq(Customer::getUserId, userId).eq(Customer::getStatus, "ACTIVE"));
        var byId = customers.stream().collect(Collectors.toMap(Customer::getId, c -> c));
        List<FollowTask> activeTasks = dueTasks.stream().filter(t -> byId.containsKey(t.getCustomerId())).toList();
        Map<Long, FollowRecord> latestRecords = customers.isEmpty() ? Map.of()
                : recordMapper.selectLatestForActiveCustomers(userId, now).stream()
                .collect(Collectors.toMap(FollowRecord::getCustomerId, r -> r));
        return new Snapshot(customers, activeTasks, latestRecords);
    }

    private static PriorityCustomer toPriorityCustomer(PriorityCandidates.Candidate candidate) {
        Customer c = candidate.customer();
        return new PriorityCustomer(candidate.taskId(), c.getId(), c.getName(), c.getStage(), c.getBudget(),
                c.getIntentionScore(), candidate.daysWithoutFollow(), candidate.planTime(), candidate.score(),
                candidate.reasonText(), candidate.assessment().hasContactHistory());
    }

    private record Snapshot(List<Customer> customers, List<FollowTask> activeTasks, Map<Long, FollowRecord> latestRecords) {}

    public record Overview(long customerCount, long todayFollowCount, long highIntentCount, long overdueCount,
                           long unscheduledCount, long todayScheduledCount, long dormantCount,
                           List<PriorityCustomer> todayTasks) {}
    public record PriorityCustomer(Long taskId, Long customerId, String customerName, String stage,
                                   BigDecimal budget, Integer intentionScore, long daysWithoutFollow,
                                   LocalDateTime planTime, double valueScore, String reasonText, boolean hasContactHistory) {}
}