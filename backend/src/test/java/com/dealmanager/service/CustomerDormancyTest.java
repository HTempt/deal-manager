package com.dealmanager.service;

import com.dealmanager.domain.Customer;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

class CustomerDormancyTest {
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 9, 12, 0);

    @Test
    void fullDaysHaveNonOverlappingSevenFifteenAndThirtyOneDayBoundaries() {
        int[] days = {0, 6, 7, 14, 15, 30, 31, 90};
        String[] levels = {"NONE", "NONE", "AT_RISK", "AT_RISK", "DORMANT", "DORMANT", "LONG_DORMANT", "LONG_DORMANT"};
        for (int i = 0; i < days.length; i++) {
            Customer customer = customer(days[i]);
            assertEquals(days[i], CustomerDormancy.days(customer, now));
            assertEquals(levels[i], CustomerDormancy.level(customer, now));
        }
        Customer customer = customer(15); customer.setLastFollowTime(now.minusDays(15).plusSeconds(1));
        assertEquals(14, CustomerDormancy.days(customer, now)); assertEquals("AT_RISK", CustomerDormancy.level(customer, now));
        customer.setLastFollowTime(now.minusDays(31).plusSeconds(1));
        assertEquals("DORMANT", CustomerDormancy.level(customer, now));
    }

    @Test
    void neverContactedCustomersUseCreationTimeAndTerminalCustomersHaveNoDormancy() {
        Customer customer = customer(18); customer.setLastFollowTime(null); customer.setFollowCount(0);
        customer.setCreatedAt(now.minusDays(18));
        CustomerDormancy.decorate(customer, now);
        assertEquals(18L, customer.getDaysWithoutFollow()); assertFalse(customer.getHasContactHistory());
        assertEquals("DORMANT", customer.getDormancyLevel());
        for (String status : new String[]{"DEAL", "LOST", "DELETED"}) {
            customer.setStatus(status); assertEquals("NONE", CustomerDormancy.level(customer, now));
        }
        customer.setCreatedAt(null); assertEquals(0, CustomerDormancy.days(customer, now));
        customer.setLastFollowTime(now.plusDays(1)); assertEquals(0, CustomerDormancy.days(customer, now));
    }

    @Test
    void schedulingAndEditingDoNotWakeCustomerButRecordingContactDoes() {
        Customer customer = customer(18);
        customer.setNextFollowTime(now.plusDays(2)); customer.setUpdatedAt(now);
        assertEquals("DORMANT", CustomerDormancy.level(customer, now));
        customer.setLastFollowTime(now); customer.setFollowCount(6);
        CustomerDormancy.decorate(customer, now);
        assertEquals("NONE", customer.getDormancyLevel()); assertEquals(0L, customer.getDaysWithoutFollow());
        assertTrue(customer.getHasContactHistory());
    }

    private Customer customer(int days) {
        Customer customer = new Customer(); customer.setId(8L); customer.setUserId(7L); customer.setStatus("ACTIVE");
        customer.setCreatedAt(now.minusDays(100)); customer.setLastFollowTime(now.minusDays(days));
        return customer;
    }
}
