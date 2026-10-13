package com.dealmanager.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dealmanager.domain.Customer;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Set;

public final class CustomerDormancy {
    private static final Set<String> FILTERS = Set.of("DORMANT_ALL", "AT_RISK", "DORMANT", "LONG_DORMANT");
    private CustomerDormancy() {}

    public static boolean isFilter(String filter) { return filter != null && FILTERS.contains(filter); }

    public static long days(Customer customer, LocalDateTime now) {
        LocalDateTime reference = customer.getLastFollowTime() == null ? customer.getCreatedAt() : customer.getLastFollowTime();
        return reference == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(reference, now));
    }

    public static String level(Customer customer, LocalDateTime now) {
        if (!"ACTIVE".equals(customer.getStatus())) return "NONE";
        long days = days(customer, now);
        return days >= 31 ? "LONG_DORMANT" : days >= 15 ? "DORMANT" : days >= 7 ? "AT_RISK" : "NONE";
    }

    public static Customer decorate(Customer customer, LocalDateTime now) {
        customer.setDaysWithoutFollow(days(customer, now));
        customer.setDormancyLevel(level(customer, now));
        customer.setHasContactHistory(customer.getLastFollowTime() != null
                || customer.getFollowCount() != null && customer.getFollowCount() > 0);
        return customer;
    }

    public static void filter(LambdaQueryWrapper<Customer> query, String filter, LocalDateTime now) {
        query.eq(Customer::getStatus, "ACTIVE");
        // Compare the same full-day cutoffs as days(), including customers never contacted.
        int minimum = "AT_RISK".equals(filter) ? 7 : "LONG_DORMANT".equals(filter) ? 31 : 15;
        query.apply("COALESCE(last_follow_time, created_at) <= {0}", now.minusDays(minimum));
        if ("AT_RISK".equals(filter) || "DORMANT".equals(filter)) {
            int upper = "AT_RISK".equals(filter) ? 15 : 31;
            query.apply("COALESCE(last_follow_time, created_at) > {0}", now.minusDays(upper));
        }
    }
}
