package com.dealmanager.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dealmanager.ai.WechatMessageClient;
import com.dealmanager.common.LogSafety;
import com.dealmanager.domain.*;
import com.dealmanager.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {
    private static final ZoneId ZONE = AiQuota.ZONE;
    private static final String DUE = "DUE", OVERDUE = "OVERDUE", DAILY = "DAILY";
    private final NotificationSubscriptionMapper subscriptionMapper;
    private final NotificationSendLogMapper logMapper;
    private final FollowTaskMapper taskMapper;
    private final CustomerMapper customerMapper;
    private final UserMapper userMapper;
    private final WechatMessageClient wechat;

    @Transactional
    public void subscribe(Long userId, String templateId, String token, Long expiresIn) {
        NotificationSubscription existing = subscriptionMapper.selectOne(new LambdaQueryWrapper<NotificationSubscription>()
                .eq(NotificationSubscription::getUserId, userId).eq(NotificationSubscription::getTemplateId, templateId)
                .eq(NotificationSubscription::getSubscribeToken, token));
        LocalDateTime now = LocalDateTime.now(ZONE);
        NotificationSubscription s = existing == null ? new NotificationSubscription() : existing;
        s.setUserId(userId); s.setTemplateId(templateId); s.setSubscribeToken(token); s.setStatus("ACTIVE");
        s.setExpiresAt(expiresIn == null || expiresIn <= 0 ? now.plusDays(1) : now.plusSeconds(expiresIn));
        s.setUpdatedAt(now);
        if (existing == null) { s.setCreatedAt(now); subscriptionMapper.insert(s); } else subscriptionMapper.updateById(s);
        log.info("notification.subscribed userId={} subscriptionId={}", userId, s.getId());
    }

    @Transactional
    public void unsubscribe(Long userId) {
        NotificationSubscription patch = new NotificationSubscription(); patch.setStatus("DISABLED"); patch.setUpdatedAt(LocalDateTime.now(ZONE));
        subscriptionMapper.update(patch, new LambdaQueryWrapper<NotificationSubscription>().eq(NotificationSubscription::getUserId, userId).eq(NotificationSubscription::getStatus, "ACTIVE"));
        setEnabled(userId, false);
    }

    @Transactional
    public void setEnabled(Long userId, boolean enabled) {
        User user = userMapper.selectById(userId);
        if (user == null) throw com.dealmanager.common.ApiException.notFound("用户不存在");
        user.setNotificationEnabled(enabled);
        user.setUpdatedAt(LocalDateTime.now(ZONE));
        userMapper.updateById(user);
        log.info("notification.settings_updated userId={} enabled={}", userId, enabled);
    }

    public boolean enabled(Long userId) {
        User user = userMapper.selectById(userId);
        return user != null && Boolean.TRUE.equals(user.getNotificationEnabled());
    }

    @Scheduled(fixedDelayString = "${app.notifications.poll-delay-ms:60000}")
    public void dispatchDue() {
        if (!wechat.configured()) {
            log.debug("notification.dispatch.skipped reason=WECHAT_NOT_CONFIGURED");
            return;
        }
        try {
            dispatchConfigured();
        } catch (Exception e) {
            // Scheduled jobs have no controller advice; keep diagnostics here without raw provider/SQL messages.
            log.error("notification.dispatch.failed errorType={}", e.getClass().getSimpleName(), LogSafety.exception(e));
        }
    }

    private void dispatchConfigured() {
        LocalDateTime now = LocalDateTime.now(ZONE);
        List<FollowTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<FollowTask>().eq(FollowTask::getStatus, "PENDING").le(FollowTask::getPlanTime, now).last("LIMIT 500"));
        log.debug("notification.dispatch.started dueTasks={}", tasks.size());
        tasks.forEach(task -> sendTask(task, task.getPlanTime().isBefore(now.toLocalDate().atStartOfDay()) ? OVERDUE : DUE));
        if (now.getHour() == 9 && now.getMinute() < 2) sendDaily(now.toLocalDate());
    }

    private void sendTask(FollowTask task, String type) {
        if (hasTaskLog(task.getId(), type)) return;
        Customer c = customerMapper.selectOne(new LambdaQueryWrapper<Customer>().eq(Customer::getId, task.getCustomerId()).eq(Customer::getUserId, task.getUserId()).eq(Customer::getStatus, "ACTIVE"));
        User u = c == null ? null : userMapper.selectById(task.getUserId());
        if (c == null || u == null) return;
        send(task.getUserId(), u, task.getId(), type, null, c.getName() + "，该跟进了", Map.of(
                "thing1", value(c.getNeed(), 20), "amount2", money(c.getBudget()), "time3", "已" + daysSince(c) + "天未联系"));
    }

    private void sendDaily(LocalDate date) {
        List<FollowTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<FollowTask>().eq(FollowTask::getStatus, "PENDING").le(FollowTask::getPlanTime, date.plusDays(1).atStartOfDay()));
        Map<Long, List<FollowTask>> grouped = tasks.stream().collect(Collectors.groupingBy(FollowTask::getUserId));
        grouped.forEach((userId, userTasks) -> {
            if (hasSummaryLog(userId, DAILY, date)) return;
            User u = userMapper.selectById(userId); if (u == null) return;
            Set<Long> ids = userTasks.stream().map(FollowTask::getCustomerId).collect(Collectors.toSet());
            long highOverdue = userTasks.stream().filter(t -> t.getPlanTime().isBefore(date.atStartOfDay())).map(FollowTask::getCustomerId).distinct().map(customerMapper::selectById).filter(Objects::nonNull).filter(c -> c.getIntentionScore() != null && c.getIntentionScore() >= 70).count();
            send(userId, u, null, DAILY, date, "今天有" + ids.size() + "位客户需要跟进", Map.of("thing1", "今日待跟进", "number2", String.valueOf(ids.size()), "thing3", "其中" + highOverdue + "位高意向客户已逾期"));
        });
    }

    private void send(Long userId, User user, Long taskId, String type, LocalDate date, String title, Map<String, String> data) {
        if (!enabled(userId)) return;
        NotificationSubscription s = subscriptionMapper.selectOne(new LambdaQueryWrapper<NotificationSubscription>().eq(NotificationSubscription::getUserId, userId).eq(NotificationSubscription::getStatus, "ACTIVE").gt(NotificationSubscription::getExpiresAt, LocalDateTime.now(ZONE)).orderByDesc(NotificationSubscription::getUpdatedAt).last("LIMIT 1"));
        if (s == null) return;
        WechatMessageClient.SendResult result = wechat.send(user.getOpenid(), s.getTemplateId(), taskId == null ? "pages/follow/index" : "pages/customer-detail/index?id=" + taskId, data);
        NotificationSendLog sendLog = new NotificationSendLog(); sendLog.setUserId(userId); sendLog.setTaskId(taskId); sendLog.setNotificationType(type); sendLog.setSummaryDate(date); sendLog.setTemplateId(s.getTemplateId()); sendLog.setStatus(result.success() ? "SENT" : "FAILED"); sendLog.setMessage(result.message()); sendLog.setSentAt(result.success() ? LocalDateTime.now(ZONE) : null); sendLog.setCreatedAt(LocalDateTime.now(ZONE));
        if (result.success()) log.info("notification.sent userId={} taskId={} type={}", userId, taskId, type);
        else log.warn("notification.send_failed userId={} taskId={} type={}", userId, taskId, type);
        try { logMapper.insert(sendLog); }
        catch (Exception e) { log.error("notification.send_log_failed userId={} taskId={} type={}", userId, taskId, type, LogSafety.exception(e)); }
        if (!result.success() && result.message().contains("订阅")) { s.setStatus("EXPIRED"); subscriptionMapper.updateById(s); }
    }

    private boolean hasTaskLog(Long id, String type) { return logMapper.selectCount(new LambdaQueryWrapper<NotificationSendLog>().eq(NotificationSendLog::getTaskId, id).eq(NotificationSendLog::getNotificationType, type)) > 0; }
    private boolean hasSummaryLog(Long userId, String type, LocalDate date) { return logMapper.selectCount(new LambdaQueryWrapper<NotificationSendLog>().eq(NotificationSendLog::getUserId, userId).eq(NotificationSendLog::getNotificationType, type).eq(NotificationSendLog::getSummaryDate, date)) > 0; }
    private static String value(String s, int max) { return StringUtils.hasText(s) ? s.substring(0, Math.min(max, s.length())) : "客户跟进"; }
    private static String money(BigDecimal n) { return n == null ? "未填写" : "¥" + n.stripTrailingZeros().toPlainString(); }
    private static long daysSince(Customer c) { LocalDateTime last = c.getLastFollowTime() == null ? c.getCreatedAt() : c.getLastFollowTime(); return Math.max(0, ChronoUnit.DAYS.between(last, LocalDateTime.now(ZONE))); }
}
