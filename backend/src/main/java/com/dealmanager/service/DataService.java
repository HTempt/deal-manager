package com.dealmanager.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dealmanager.domain.Customer;
import com.dealmanager.domain.Deal;
import com.dealmanager.mapper.CustomerMapper;
import com.dealmanager.mapper.DealMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DataService {
    private final CustomerMapper customerMapper;
    private final DealMapper dealMapper;

    public Overview overview(Long userId, YearMonth month) {
        LocalDateTime start = month.atDay(1).atStartOfDay();
        LocalDateTime end = month.plusMonths(1).atDay(1).atStartOfDay();
        LocalDateTime previousStart = month.minusMonths(1).atDay(1).atStartOfDay();
        long newCustomers = customerMapper.selectCount(new LambdaQueryWrapper<Customer>().eq(Customer::getUserId, userId)
                .ge(Customer::getCreatedAt, start).lt(Customer::getCreatedAt, end).ne(Customer::getStatus, "DELETED"));
        long previous = customerMapper.selectCount(new LambdaQueryWrapper<Customer>().eq(Customer::getUserId, userId)
                .ge(Customer::getCreatedAt, previousStart).lt(Customer::getCreatedAt, start).ne(Customer::getStatus, "DELETED"));
        List<Deal> deals = deals(userId, start, end);
        BigDecimal amount = deals.stream().map(Deal::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal growth = previous == 0 ? null
                : BigDecimal.valueOf((newCustomers - previous) * 100.0 / previous).setScale(1, RoundingMode.HALF_UP);
        return new Overview(month.toString(), newCustomers, growth, amount, deals.stream().map(Deal::getCustomerId).distinct().count());
    }

    public Funnel funnel(Long userId, YearMonth month) {
        LocalDateTime start = month.atDay(1).atStartOfDay();
        LocalDateTime end = month.plusMonths(1).atDay(1).atStartOfDay();
        List<Customer> cohort = customerMapper.selectList(new LambdaQueryWrapper<Customer>()
                .eq(Customer::getUserId, userId).ge(Customer::getCreatedAt, start)
                .lt(Customer::getCreatedAt, end).ne(Customer::getStatus, "DELETED"));
        long[] reached = new long[5];
        long deals = 0, lost = 0;
        for (Customer customer : cohort) {
            CustomerFunnel.advance(customer);
            for (int rank = 0; rank < reached.length; rank++) {
                if (customer.getFunnelStageRank() >= rank) reached[rank]++;
            }
            if ("DEAL".equals(customer.getStatus())) deals++;
            if ("LOST".equals(customer.getStatus())) lost++;
        }
        List<String> names = List.of("新增客户", "需求沟通", "方案中", "报价中", "谈判中", "成交");
        List<FunnelStage> stages = new ArrayList<>();
        for (int rank = 0; rank < reached.length; rank++) stages.add(new FunnelStage(names.get(rank), reached[rank]));
        stages.add(new FunnelStage(names.get(5), deals));
        return new Funnel(month.toString(), "所选月份新增且未删除的客户",
                "截至当前的累计阶段进展，跳阶段计入前置阶段", List.copyOf(stages), lost,
                percent(deals, reached[0]), percent(deals, reached[3]), percent(reached[3], reached[0]));
    }

    private static BigDecimal percent(long numerator, long denominator) {
        return denominator == 0 ? null : BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 1, RoundingMode.HALF_UP);
    }

    public List<SourceStat> sources(Long userId, YearMonth month) {
        LocalDateTime start = month.atDay(1).atStartOfDay();
        LocalDateTime end = month.plusMonths(1).atDay(1).atStartOfDay();
        List<Customer> customers = customerMapper.selectList(new LambdaQueryWrapper<Customer>().eq(Customer::getUserId, userId)
                .ge(Customer::getCreatedAt, start).lt(Customer::getCreatedAt, end).ne(Customer::getStatus, "DELETED"));
        List<Deal> deals = deals(userId, start, end);
        Set<String> names = new LinkedHashSet<>(List.of("抖音", "微信", "小红书", "转介绍", "其他"));
        customers.forEach(c -> names.add(source(c.getSource())));
        deals.forEach(d -> names.add(source(d.getSource())));
        return names.stream().map(name -> new SourceStat(name,
                customers.stream().filter(c -> source(c.getSource()).equals(name)).count(),
                deals.stream().filter(d -> source(d.getSource()).equals(name)).map(Deal::getCustomerId).distinct().count(),
                deals.stream().filter(d -> source(d.getSource()).equals(name)).map(Deal::getAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)))
                .filter(s -> s.newCustomers() > 0 || s.dealCustomers() > 0).toList();
    }

    public List<Deal> dealList(Long userId, YearMonth month) {
        return deals(userId, month.atDay(1).atStartOfDay(), month.plusMonths(1).atDay(1).atStartOfDay());
    }

    private List<Deal> deals(Long userId, LocalDateTime start, LocalDateTime end) {
        return dealMapper.selectList(new LambdaQueryWrapper<Deal>().eq(Deal::getUserId, userId)
                .apply("EXISTS (SELECT 1 FROM customer c WHERE c.id = deal.customer_id AND c.user_id = {0} AND c.status <> 'DELETED')", userId)
                .ge(Deal::getDealTime, start).lt(Deal::getDealTime, end).orderByDesc(Deal::getDealTime));
    }
    private static String source(String source) { return source == null || source.isBlank() ? "其他" : source; }

    public record Funnel(String month, String cohortBasis, String progressBasis, List<FunnelStage> stages,
                         long lostCustomers, BigDecimal dealConversionPercent, BigDecimal quoteToDealPercent,
                         BigDecimal inquiryToQuotePercent) {}
    public record FunnelStage(String stage, long customers) {}

    public record Overview(String month, long newCustomers, BigDecimal customerGrowthPercent,
                           BigDecimal dealAmount, long dealCustomers) {}
    public record SourceStat(String source, long newCustomers, long dealCustomers, BigDecimal dealAmount) {}
}
