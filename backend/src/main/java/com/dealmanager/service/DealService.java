package com.dealmanager.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dealmanager.domain.Customer;
import com.dealmanager.domain.Deal;
import com.dealmanager.domain.FollowTask;
import com.dealmanager.mapper.CustomerMapper;
import com.dealmanager.mapper.DealMapper;
import com.dealmanager.mapper.FollowTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class DealService {
    private final DealMapper dealMapper;
    private final CustomerMapper customerMapper;
    private final FollowTaskMapper taskMapper;
    private final CustomerService customerService;

    @Transactional
    public Deal create(Long userId, Long customerId, BigDecimal amount, String productName, LocalDateTime dealTime) {
        Customer customer = customerService.getForUpdate(userId, customerId);
        CustomerFunnel.advance(customer);
        Deal deal = new Deal();
        deal.setUserId(userId);
        deal.setCustomerId(customerId);
        deal.setAmount(amount);
        deal.setProductName(productName);
        deal.setDealTime(dealTime == null ? LocalDateTime.now() : dealTime);
        deal.setSource(customer.getSource());
        deal.setCreatedAt(LocalDateTime.now());
        dealMapper.insert(deal);

        customer.setStatus("DEAL");
        customer.setStage("已成交");
        CustomerFunnel.advance(customer);
        customer.setDealAmount(amount);
        customer.setDealTime(deal.getDealTime());
        customer.setNextFollowTime(null);
        customer.setUpdatedAt(LocalDateTime.now());
        customerMapper.updateById(customer);

        FollowTask patch = new FollowTask();
        patch.setStatus("CANCELLED");
        taskMapper.update(patch, new LambdaQueryWrapper<FollowTask>().eq(FollowTask::getUserId, userId)
                .eq(FollowTask::getCustomerId, customerId).eq(FollowTask::getStatus, "PENDING"));
        log.info("deal.saved userId={} customerId={} dealId={}", userId, customerId, deal.getId());
        return deal;
    }
}
