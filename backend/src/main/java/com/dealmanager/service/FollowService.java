package com.dealmanager.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dealmanager.common.ApiException;
import com.dealmanager.domain.Customer;
import com.dealmanager.domain.FollowRecord;
import com.dealmanager.domain.FollowTask;
import com.dealmanager.mapper.CustomerMapper;
import com.dealmanager.mapper.FollowRecordMapper;
import com.dealmanager.mapper.FollowTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.ArrayList;

@Service
@RequiredArgsConstructor
@Slf4j
public class FollowService {
    private final FollowTaskMapper taskMapper;
    private final FollowRecordMapper recordMapper;
    private final CustomerMapper customerMapper;
    private final CustomerService customerService;

    public List<TaskView> today(Long userId) {
        LocalDateTime start = LocalDate.now(AiQuota.ZONE).atStartOfDay();
        LocalDateTime end = start.plusDays(1);
        return views(userId, new LambdaQueryWrapper<FollowTask>().eq(FollowTask::getUserId, userId)
                .eq(FollowTask::getStatus, "PENDING").ge(FollowTask::getPlanTime, start)
                .lt(FollowTask::getPlanTime, end).orderByAsc(FollowTask::getPlanTime));
    }

    public List<TaskView> overdue(Long userId) {
        LocalDateTime start = LocalDate.now(AiQuota.ZONE).atStartOfDay();
        return views(userId, new LambdaQueryWrapper<FollowTask>().eq(FollowTask::getUserId, userId)
                .eq(FollowTask::getStatus, "PENDING").lt(FollowTask::getPlanTime, start)
                .orderByAsc(FollowTask::getPlanTime));
    }

    public List<TaskView> upcoming(Long userId) {
        LocalDateTime tomorrow = LocalDate.now(AiQuota.ZONE).plusDays(1).atStartOfDay();
        return views(userId, new LambdaQueryWrapper<FollowTask>().eq(FollowTask::getUserId, userId)
                .eq(FollowTask::getStatus, "PENDING").ge(FollowTask::getPlanTime, tomorrow)
                .orderByAsc(FollowTask::getPlanTime).orderByAsc(FollowTask::getId));
    }

    private List<TaskView> views(Long userId, LambdaQueryWrapper<FollowTask> query) {
        return taskMapper.selectList(query).stream().map(task -> {
            Customer c = customerMapper.selectOne(new LambdaQueryWrapper<Customer>()
                    .eq(Customer::getId, task.getCustomerId()).eq(Customer::getUserId, userId)
                    .eq(Customer::getStatus, "ACTIVE"));
            if (c == null) return null;
            long overdueDays = Math.max(0, ChronoUnit.DAYS.between(task.getPlanTime().toLocalDate(), LocalDate.now(AiQuota.ZONE)));
            return new TaskView(task.getId(), c.getId(), c.getName(), task.getTitle(), c.getStage(), c.getBudget(),
                    c.getIntentionScore(), task.getPlanTime(), overdueDays);
        }).filter(java.util.Objects::nonNull).toList();
    }

    @Transactional
    public FollowRecord create(Long userId, FollowCommand command) {
        Customer customer = customerService.getForUpdate(userId, command.customerId());
        LocalDateTime now = LocalDateTime.now(AiQuota.ZONE);
        if (command.content() == null || command.content().isBlank()) throw ApiException.badRequest("请填写客户反馈");
        if (!List.of("没有回应", "有兴趣", "考虑中", "准备成交", "已流失").contains(command.customerFeedback() == null ? "" : command.customerFeedback())) {
            throw ApiException.badRequest("请选择客户反馈状态");
        }
        CustomerFunnel.advance(customer);
        int before = customer.getIntentionScore() == null ? 10 : customer.getIntentionScore();
        FollowRecord record = new FollowRecord();
        record.setCustomerId(customer.getId());
        record.setUserId(userId);
        record.setFollowType(command.followType());
        record.setContent(command.content());
        record.setCustomerFeedback(command.customerFeedback());
        boolean replied = !List.of("没有回应", "已流失").contains(command.customerFeedback());
        record.setActiveInquiry(replied && Boolean.TRUE.equals(command.activeInquiry()));
        record.setPriceInquiry(replied && Boolean.TRUE.equals(command.priceInquiry()));
        record.setNextFollowTime(command.nextFollowTime());
        record.setCreatedAt(now);
        customer.setLastFollowTime(now);
        customer.setNextFollowTime(command.nextFollowTime());
        customer.setFollowCount((customer.getFollowCount() == null ? 0 : customer.getFollowCount()) + 1);
        if (command.customerStatus() != null) {
            if ("已流失".equals(command.customerStatus())) { customer.setStatus("LOST"); customer.setStage("已流失"); }
            else if (!List.of("已成交", "没有回应", "有兴趣", "考虑中", "准备成交").contains(command.customerStatus())) customer.setStage(command.customerStatus());
        }
        CustomerFunnel.advance(customer);
        if ("已流失".equals(command.customerFeedback())) { customer.setStatus("LOST"); customer.setStage("已流失"); }
        CustomerFunnel.advance(customer);
        List<FollowRecord> history = new ArrayList<>(customerService.intentionHistory(userId, customer.getId(), now));
        history.add(record);
        customerService.score(customer, command.intentionMode(), command.intentionScore(), history, now);
        record.setIntentionBefore(before);
        record.setIntentionAfter(customer.getIntentionScore());
        record.setIntentionChange(customer.getIntentionScore() - before);
        record.setIntentionMode(customer.getIntentionMode());
        recordMapper.insert(record);
        customer.setUpdatedAt(now);
        customerMapper.updateById(customer);

        completePending(userId, customer.getId(), command.taskId(), now);
        if (command.nextFollowTime() != null && !List.of("LOST", "DEAL").contains(customer.getStatus())) {
            FollowTask task = new FollowTask();
            task.setUserId(userId);
            task.setCustomerId(customer.getId());
            task.setTaskType("CUSTOMER_FOLLOW");
            task.setTitle("继续跟进");
            task.setPlanTime(command.nextFollowTime());
            task.setStatus("PENDING");
            task.setCreatedAt(now);
            taskMapper.insert(task);
        }
        log.info("follow.saved userId={} customerId={} recordId={} intentionMode={} intentionBefore={} intentionAfter={} scheduled={}",
                userId, customer.getId(), record.getId(), record.getIntentionMode(), before, customer.getIntentionScore(),
                command.nextFollowTime() != null && "ACTIVE".equals(customer.getStatus()));
        return record;
    }

    @Transactional
    public void complete(Long userId, Long taskId) {
        FollowTask task = taskMapper.selectOne(new LambdaQueryWrapper<FollowTask>()
                .eq(FollowTask::getId, taskId).eq(FollowTask::getUserId, userId));
        if (task == null) throw ApiException.notFound("跟进任务不存在");
        if (!"PENDING".equals(task.getStatus())) return;
        task.setStatus("COMPLETED");
        task.setCompletedTime(LocalDateTime.now());
        taskMapper.updateById(task);
        log.info("follow_task.completed userId={} taskId={} customerId={}", userId, taskId, task.getCustomerId());
    }

    private void completePending(Long userId, Long customerId, Long taskId, LocalDateTime now) {
        FollowTask patch = new FollowTask();
        patch.setStatus("COMPLETED");
        patch.setCompletedTime(now);
        LambdaQueryWrapper<FollowTask> query = new LambdaQueryWrapper<FollowTask>()
                .eq(FollowTask::getUserId, userId).eq(FollowTask::getStatus, "PENDING");
        if (taskId != null) query.eq(FollowTask::getId, taskId);
        else query.eq(FollowTask::getCustomerId, customerId).le(FollowTask::getPlanTime, now);
        taskMapper.update(patch, query);
    }

    public record FollowCommand(Long customerId, Long taskId, String followType, String content,
                                String customerFeedback, String customerStatus, Integer intentionChange,
                                Boolean activeInquiry, Boolean priceInquiry, Integer intentionScore,
                                String intentionMode, LocalDateTime nextFollowTime) {}
    public record TaskView(Long taskId, Long customerId, String customerName, String title, String stage,
                           BigDecimal budget, Integer intentionScore, LocalDateTime planTime, long overdueDays) {}
}
