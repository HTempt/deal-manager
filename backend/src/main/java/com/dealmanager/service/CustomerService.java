package com.dealmanager.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class CustomerService {
    private static final String CUSTOMER_NAME_REGEX = "[\\u4e00-\\u9fffA-Za-z0-9]+";

    private final CustomerMapper customerMapper;
    private final FollowRecordMapper recordMapper;
    private final FollowTaskMapper taskMapper;
    private final SettingsService settingsService;

    public Page<Customer> list(Long userId, String keyword, String filter, int page, int size) {
        LambdaQueryWrapper<Customer> query = new LambdaQueryWrapper<Customer>()
                .eq(Customer::getUserId, userId).ne(Customer::getStatus, "DELETED");
        if (StringUtils.hasText(keyword)) {
            query.and(q -> q.like(Customer::getName, keyword).or().like(Customer::getPhone, keyword).or().like(Customer::getNeed, keyword));
        }

        // 新增客户默认安排在明天跟进，不能只筛选 next_follow_time <= 当前时间。
        if ("FOLLOW".equals(filter)) query.isNotNull(Customer::getNextFollowTime).eq(Customer::getStatus, "ACTIVE");
        if ("UNSCHEDULED".equals(filter)) query.isNull(Customer::getNextFollowTime).eq(Customer::getStatus, "ACTIVE");
        if ("HIGH".equals(filter)) query.ge(Customer::getIntentionScore, 70).eq(Customer::getStatus, "ACTIVE");
        if ("QUOTED".equals(filter)) query.eq(Customer::getStage, "报价中");
        if ("DEAL".equals(filter)) query.eq(Customer::getStatus, "DEAL");
        if ("LOST".equals(filter)) query.eq(Customer::getStatus, "LOST");
        LocalDateTime now = LocalDateTime.now(AiQuota.ZONE);
        if (CustomerDormancy.isFilter(filter)) {
            CustomerDormancy.filter(query, filter, now);
            query.orderByDesc(Customer::getIntentionScore).orderByDesc(Customer::getBudget)
                    .orderByAsc(Customer::getLastFollowTime).orderByAsc(Customer::getCreatedAt).orderByAsc(Customer::getId);
        } else query.orderByDesc(Customer::getUpdatedAt);
        Page<Customer> result = customerMapper.selectPage(Page.of(Math.max(page, 1), Math.min(Math.max(size, 1), 100)), query);
        result.getRecords().forEach(customer -> CustomerDormancy.decorate(customer, now));
        return result;
    }

    public Customer get(Long userId, Long id) {
        Customer customer = customerMapper.selectOne(new LambdaQueryWrapper<Customer>()
                .eq(Customer::getId, id).eq(Customer::getUserId, userId).ne(Customer::getStatus, "DELETED"));
        if (customer == null) throw ApiException.notFound("客户不存在");
        return CustomerDormancy.decorate(customer, LocalDateTime.now(AiQuota.ZONE));
    }

    public List<FollowRecord> records(Long userId, Long customerId) {
        get(userId, customerId);
        return recordMapper.selectList(new LambdaQueryWrapper<FollowRecord>()
                .eq(FollowRecord::getUserId, userId).eq(FollowRecord::getCustomerId, customerId)
                .orderByDesc(FollowRecord::getCreatedAt));
    }

    public Customer getForUpdate(Long userId, Long id) {
        Customer customer = customerMapper.selectOne(new LambdaQueryWrapper<Customer>()
                .eq(Customer::getId, id).eq(Customer::getUserId, userId).ne(Customer::getStatus, "DELETED").last("FOR UPDATE"));
        if (customer == null) throw ApiException.notFound("客户不存在");
        return customer;
    }

    public List<FollowRecord> intentionHistory(Long userId, Long customerId, LocalDateTime now) {
        return recordMapper.selectList(new LambdaQueryWrapper<FollowRecord>()
                .eq(FollowRecord::getUserId, userId).eq(FollowRecord::getCustomerId, customerId)
                .ge(FollowRecord::getCreatedAt, now.minusDays(30)).le(FollowRecord::getCreatedAt, now)
                .orderByDesc(FollowRecord::getCreatedAt).orderByDesc(FollowRecord::getId));
    }

    public void score(Customer customer, String requestedMode, Integer manualScore, List<FollowRecord> history, LocalDateTime now) {
        String mode = IntentionScore.mode(requestedMode, customer.getIntentionMode());
        int value = "MANUAL".equals(mode) ? IntentionScore.manual(manualScore == null ? customer.getIntentionScore() : manualScore)
                : IntentionScore.calculate(customer, history, now);
        customer.setIntentionMode(mode);
        customer.setIntentionScore(value);
    }

    @Transactional
    public Customer create(Long userId, CustomerCommand command) {
        long customerCount = customerMapper.selectCount(new LambdaQueryWrapper<Customer>()
                .eq(Customer::getUserId, userId).ne(Customer::getStatus, "DELETED"));
        if ("FREE".equals(settingsService.user(userId).getMembershipType()) && customerCount >= 100) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "免费版最多可添加 100 位客户，请升级会员");
        }
        LocalDateTime now = LocalDateTime.now(AiQuota.ZONE);
        Customer customer = new Customer();
        apply(customer, command);
        customer.setUserId(userId);
        customer.setStage(defaultText(command.stage(), "新客户"));
        customer.setFollowCount(0);
        customer.setDealAmount(BigDecimal.ZERO);
        customer.setStatus("ACTIVE");
        CustomerFunnel.advance(customer);
        score(customer, command.intentionMode(), command.intentionScore(), List.of(), now);
        customer.setCreatedAt(now);
        customer.setUpdatedAt(now);
        customerMapper.insert(customer);
        createTask(userId, customer, command.nextFollowTime());
        log.info("customer.created userId={} customerId={} tagCount={} intentionMode={} scheduled={}",
                userId, customer.getId(), customer.getTags().size(), customer.getIntentionMode(), customer.getNextFollowTime() != null);
        return customer;
    }

    @Transactional
    public Customer update(Long userId, Long id, CustomerCommand command) {
        Customer customer = getForUpdate(userId, id);
        CustomerFunnel.advance(customer);
        LocalDateTime previousNext = customer.getNextFollowTime();
        apply(customer, command);
        CustomerFunnel.advance(customer);
        LocalDateTime now = LocalDateTime.now(AiQuota.ZONE);
        score(customer, command.intentionMode(), command.intentionScore(), intentionHistory(userId, id, now), now);
        customer.setUpdatedAt(now);
        customerMapper.updateById(customer);
        if (!Objects.equals(command.nextFollowTime(), previousNext)) {
            cancelPendingTasks(userId, id);
            createTask(userId, customer, command.nextFollowTime());
        }
        log.info("customer.updated userId={} customerId={} tagCount={} intentionMode={} scheduleChanged={}",
                userId, id, customer.getTags().size(), customer.getIntentionMode(), !Objects.equals(command.nextFollowTime(), previousNext));
        return customer;
    }

    @Transactional
    public void delete(Long userId, Long id) {
        Customer customer = get(userId, id);
        customer.setStatus("DELETED");
        customer.setUpdatedAt(LocalDateTime.now());
        customerMapper.updateById(customer);
        cancelPendingTasks(userId, id);
        log.info("customer.deleted userId={} customerId={}", userId, id);
    }

    @Transactional
    public Customer schedule(Long userId, Long id, LocalDateTime nextFollowTime) {
        LocalDateTime now = LocalDateTime.now(AiQuota.ZONE);
        if (nextFollowTime == null || !nextFollowTime.isAfter(now)) {
            throw ApiException.badRequest("请选择未来的跟进时间");
        }
        // Lock the customer while replacing its tasks, including simultaneous quick actions.
        Customer customer = customerMapper.selectOne(new LambdaQueryWrapper<Customer>()
                .eq(Customer::getId, id).eq(Customer::getUserId, userId)
                .ne(Customer::getStatus, "DELETED").last("FOR UPDATE"));
        if (customer == null) throw ApiException.notFound("客户不存在");
        if (!"ACTIVE".equals(customer.getStatus())) throw ApiException.badRequest("已成交或已流失客户不能安排跟进");
        if (Objects.equals(nextFollowTime, customer.getNextFollowTime())) return customer;
        customer.setNextFollowTime(nextFollowTime);
        customer.setUpdatedAt(now);
        customerMapper.updateById(customer);
        cancelPendingTasks(userId, id);
        createTask(userId, customer, nextFollowTime);
        log.info("customer.scheduled userId={} customerId={} nextFollowTime={}", userId, id, nextFollowTime);
        return customer;
    }

    private void apply(Customer c, CustomerCommand command) {
        if (!StringUtils.hasText(command.name()) || !command.name().matches(CUSTOMER_NAME_REGEX)) {
            throw ApiException.badRequest("客户姓名只能包含中文、字母或数字");
        }
        if (!StringUtils.hasText(command.city())) {
            throw ApiException.badRequest("请输入客户所在城市");
        }
        c.setName(command.name().trim());
        c.setCity(command.city().trim());
        c.setAddress(StringUtils.hasText(command.address()) ? command.address().trim() : null);
        c.setPhone(command.phone());
        c.setNeed(command.need().trim());
        c.setBudget(command.budget());
        c.setSource(command.source());
        c.setTags(normalizeTags(command.tags()));
        if (StringUtils.hasText(command.stage())) c.setStage(command.stage());
        c.setNextFollowTime(command.nextFollowTime());
    }

    private static List<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) return new ArrayList<>();
        if (tags.size() > 5) throw ApiException.badRequest("客户标签最多选择 5 个");
        Set<String> unique = new LinkedHashSet<>();
        for (String tag : tags) {
            if (!StringUtils.hasText(tag)) throw ApiException.badRequest("客户标签不能为空");
            String value = tag.trim();
            if (value.length() > 20) throw ApiException.badRequest("客户标签不能超过 20 个字");
            unique.add(value);
        }
        if (unique.size() > 5) throw ApiException.badRequest("客户标签最多选择 5 个");
        return new ArrayList<>(unique);
    }

    private void createTask(Long userId, Customer customer, LocalDateTime planTime) {
        if (planTime == null) return;
        FollowTask task = new FollowTask();
        task.setUserId(userId);
        task.setCustomerId(customer.getId());
        task.setTaskType("CUSTOMER_FOLLOW");
        task.setTitle(defaultText(customer.getStage(), "客户") + "跟进");
        task.setPlanTime(planTime);
        task.setStatus("PENDING");
        task.setCreatedAt(LocalDateTime.now());
        taskMapper.insert(task);
    }

    private void cancelPendingTasks(Long userId, Long customerId) {
        FollowTask patch = new FollowTask();
        patch.setStatus("CANCELLED");
        taskMapper.update(patch, new LambdaQueryWrapper<FollowTask>().eq(FollowTask::getUserId, userId)
                .eq(FollowTask::getCustomerId, customerId).eq(FollowTask::getStatus, "PENDING"));
    }

    private static String defaultText(String value, String fallback) { return StringUtils.hasText(value) ? value : fallback; }

    public record CustomerCommand(String name, String city, String address, String phone, String need, BigDecimal budget, String source, List<String> tags,
                                  String stage, Integer intentionScore, String intentionMode, LocalDateTime nextFollowTime) {
        /** Compatibility constructor for internal callers created before location fields were added. */
        public CustomerCommand(String name, String phone, String need, BigDecimal budget, String source, List<String> tags,
                               String stage, Integer intentionScore, String intentionMode, LocalDateTime nextFollowTime) {
            this(name, "未填写", null, phone, need, budget, source, tags, stage, intentionScore, intentionMode, nextFollowTime);
        }
    }
}
