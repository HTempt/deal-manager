package com.dealmanager.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dealmanager.domain.AiRecord;
import com.dealmanager.domain.Customer;
import com.dealmanager.domain.User;
import com.dealmanager.mapper.AiRecordMapper;
import com.dealmanager.mapper.CustomerMapper;
import com.dealmanager.service.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import com.dealmanager.service.AiQuota;
import java.util.Map;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {
    private final SettingsService settingsService;
    private final CustomerMapper customerMapper;
    private final AiRecordMapper aiRecordMapper;

    @GetMapping("/profile")
    public Map<String, Object> profile(@AuthenticationPrincipal Long userId) {
        User user = settingsService.user(userId);
        long customers = customerMapper.selectCount(new LambdaQueryWrapper<Customer>()
                .eq(Customer::getUserId, userId).ne(Customer::getStatus, "DELETED"));
        java.time.LocalDateTime dayStart = AiQuota.dayStart();
        long aiUsage = aiRecordMapper.selectCount(new LambdaQueryWrapper<AiRecord>().eq(AiRecord::getUserId, userId)
                .ge(AiRecord::getCreatedAt, dayStart).lt(AiRecord::getCreatedAt, dayStart.plusDays(1)));
        int customerLimit = "FREE".equals(user.getMembershipType()) ? 100 : -1;
        int aiLimit = AiQuota.unlimited(user) ? -1 : AiQuota.FREE_DAILY_LIMIT;
        return Map.of("user", user, "customerUsage", customers, "customerLimit", customerLimit,
                "aiUsage", aiUsage, "aiUsed", aiUsage, "aiLimit", aiLimit);
    }

    @PutMapping("/profile")
    public User update(@AuthenticationPrincipal Long userId, @RequestBody ProfileRequest request) {
        return settingsService.updateProfile(userId, request.nickname(), request.phone());
    }
    public record ProfileRequest(String nickname, String phone) {}
}
