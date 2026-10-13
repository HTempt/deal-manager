package com.dealmanager.service;

import com.dealmanager.domain.User;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class AiQuotaTest {
    @Test
    void freeUserHasDailyLimitOfTen() {
        assertEquals(10, AiQuota.FREE_DAILY_LIMIT);
        User user = user("FREE", null);
        assertFalse(AiQuota.unlimited(user));
    }

    @Test
    void activeVipIsUnlimited() {
        assertTrue(AiQuota.unlimited(user("VIP", LocalDateTime.now(AiQuota.ZONE).plusMinutes(1))));
        assertTrue(AiQuota.unlimited(user("PRO", LocalDateTime.now(AiQuota.ZONE).plusDays(1))));
    }

    @Test
    void expiredVipFallsBackToFreeQuota() {
        User expiredVip = user("VIP", LocalDateTime.now(AiQuota.ZONE).minusSeconds(1));
        User expiredPro = user("PRO", LocalDateTime.now(AiQuota.ZONE).minusDays(1));
        assertFalse(AiQuota.unlimited(expiredVip));
        assertFalse(AiQuota.unlimited(expiredPro));
    }

    private static User user(String membershipType, LocalDateTime expiry) {
        User user = new User();
        user.setMembershipType(membershipType);
        user.setMembershipExpireTime(expiry);
        return user;
    }
}
