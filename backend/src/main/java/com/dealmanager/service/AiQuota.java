package com.dealmanager.service;

import com.dealmanager.domain.User;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

public final class AiQuota {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final int FREE_DAILY_LIMIT = 10;
    private AiQuota() {}
    public static LocalDateTime dayStart() { return LocalDate.now(ZONE).atStartOfDay(); }
    public static boolean unlimited(User user) {
        return ("VIP".equals(user.getMembershipType()) || "PRO".equals(user.getMembershipType()))
                && (user.getMembershipExpireTime() == null || user.getMembershipExpireTime().isAfter(LocalDateTime.now(ZONE)));
    }
}
