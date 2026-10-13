package com.dealmanager.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dealmanager.domain.Customer;
import com.dealmanager.domain.Deal;
import com.dealmanager.domain.FollowRecord;
import com.dealmanager.domain.FollowTask;
import com.dealmanager.mapper.CustomerMapper;
import com.dealmanager.mapper.DealMapper;
import com.dealmanager.mapper.FollowRecordMapper;
import com.dealmanager.mapper.FollowTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FollowEfficiencyService {
    private final CustomerMapper customerMapper;
    private final FollowTaskMapper taskMapper;
    private final FollowRecordMapper recordMapper;
    private final DealMapper dealMapper;

    public Efficiency efficiency(Long userId, YearMonth month) {
        return efficiency(userId, month, LocalDateTime.now(AiQuota.ZONE));
    }

    Efficiency efficiency(Long userId, YearMonth month, LocalDateTime now) {
        LocalDateTime start = month.atDay(1).atStartOfDay();
        LocalDateTime end = month.plusMonths(1).atDay(1).atStartOfDay();
        Map<Long, Customer> customers = customerMapper.selectList(new LambdaQueryWrapper<Customer>()
                .select(Customer::getId, Customer::getCreatedAt).eq(Customer::getUserId, userId)
                .ne(Customer::getStatus, "DELETED")).stream().collect(Collectors.toMap(Customer::getId, c -> c));
        var tasks = taskMapper.selectList(new LambdaQueryWrapper<FollowTask>()
                .select(FollowTask::getCustomerId, FollowTask::getStatus, FollowTask::getCompletedTime)
                .eq(FollowTask::getUserId, userId).in(FollowTask::getStatus, "PENDING", "COMPLETED")
                .ge(FollowTask::getPlanTime, start).lt(FollowTask::getPlanTime, end).le(FollowTask::getPlanTime, now))
                .stream().filter(t -> customers.containsKey(t.getCustomerId())).toList();
        long completed = tasks.stream().filter(t -> "COMPLETED".equals(t.getStatus())
                && (t.getCompletedTime() == null || !t.getCompletedTime().isAfter(now))).count();
        var records = recordMapper.selectList(new LambdaQueryWrapper<FollowRecord>()
                .select(FollowRecord::getCustomerId).eq(FollowRecord::getUserId, userId)
                .ge(FollowRecord::getCreatedAt, start).lt(FollowRecord::getCreatedAt, end).le(FollowRecord::getCreatedAt, now))
                .stream().filter(r -> customers.containsKey(r.getCustomerId())).toList();
        long contacted = records.stream().map(FollowRecord::getCustomerId).distinct().count();

        // Use the first real deal across history: repeat purchases must not inflate acquisition cycles.
        Map<Long, LocalDateTime> firstDeals = dealMapper.selectList(new LambdaQueryWrapper<Deal>()
                .select(Deal::getCustomerId, Deal::getDealTime).eq(Deal::getUserId, userId).le(Deal::getDealTime, now))
                .stream().filter(d -> customers.containsKey(d.getCustomerId()) && d.getDealTime() != null)
                .collect(Collectors.toMap(Deal::getCustomerId, Deal::getDealTime, (a, b) -> a.isBefore(b) ? a : b));
        long samples = 0;
        BigDecimal totalSeconds = BigDecimal.ZERO;
        for (var entry : firstDeals.entrySet()) {
            LocalDateTime first = entry.getValue(), created = customers.get(entry.getKey()).getCreatedAt();
            if (first.isBefore(start) || !first.isBefore(end) || created == null || first.isBefore(created)) continue;
            totalSeconds = totalSeconds.add(BigDecimal.valueOf(Duration.between(created, first).getSeconds()));
            samples++;
        }
        BigDecimal cycle = samples == 0 ? null : totalSeconds.divide(BigDecimal.valueOf(samples).multiply(BigDecimal.valueOf(86400)), 1, RoundingMode.HALF_UP);
        return new Efficiency(month.toString(), now, tasks.size(), completed, tasks.size() - completed,
                ratio(completed * 100, tasks.size()), records.size(), contacted, ratio(records.size(), contacted), samples, cycle);
    }

    private static BigDecimal ratio(long numerator, long denominator) {
        return denominator == 0 ? null : BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 1, RoundingMode.HALF_UP);
    }

    public record Efficiency(String month, LocalDateTime asOf, long expectedTasks, long completedTasks, long pendingTasks,
                             BigDecimal completionPercent, long followRecords, long followedCustomers,
                             BigDecimal averageFollowCount, long dealCycleCustomers, BigDecimal averageDealCycleDays) {}
}
