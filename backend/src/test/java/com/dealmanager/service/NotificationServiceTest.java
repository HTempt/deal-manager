package com.dealmanager.service;

import com.dealmanager.ai.WechatMessageClient;
import com.dealmanager.common.ApiException;
import com.dealmanager.domain.Customer;
import com.dealmanager.domain.FollowTask;
import com.dealmanager.domain.User;
import com.dealmanager.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class NotificationServiceTest {
    private UserMapper users;
    private NotificationSubscriptionMapper subscriptions;
    private NotificationSendLogMapper logs;
    private FollowTaskMapper tasks;
    private CustomerMapper customers;
    private WechatMessageClient wechat;
    private NotificationService service;
    private User user;

    @BeforeEach
    void setUp() {
        users = mock(UserMapper.class);
        subscriptions = mock(NotificationSubscriptionMapper.class);
        logs = mock(NotificationSendLogMapper.class);
        tasks = mock(FollowTaskMapper.class);
        customers = mock(CustomerMapper.class);
        wechat = mock(WechatMessageClient.class);
        service = new NotificationService(subscriptions, logs, tasks, customers, users, wechat);
        user = new User(); user.setId(7L);
        when(users.selectById(7L)).thenReturn(user);
    }

    @Test
    void nullOrFalsePreferenceIsDisabled() {
        assertFalse(service.enabled(7L));
        user.setNotificationEnabled(false);
        assertFalse(service.enabled(7L));
        assertFalse(service.enabled(8L));
    }

    @Test
    void persistsPreferenceForCurrentUserAndCanTurnOffAgain() {
        service.setEnabled(7L, true);
        assertTrue(service.enabled(7L));
        assertNotNull(user.getUpdatedAt());
        service.setEnabled(7L, false);
        assertFalse(service.enabled(7L));
        verify(users, times(2)).updateById(user);
        verify(users, never()).selectById(8L);
    }

    @Test
    void nonexistentUserCannotChangeSettings() {
        assertThrows(ApiException.class, () -> service.setEnabled(8L, true));
        verify(users, never()).updateById(any(User.class));
    }

    @Test
    void disabledUserDoesNotConsumeAuthorizationOrSendDueMessages() {
        user.setNotificationEnabled(false);
        when(wechat.configured()).thenReturn(true);
        FollowTask task = new FollowTask();
        task.setId(10L); task.setUserId(7L); task.setCustomerId(8L);
        task.setPlanTime(LocalDateTime.now(AiQuota.ZONE).minusMinutes(1));
        when(tasks.selectList(any())).thenReturn(List.of(task));
        when(logs.selectCount(any())).thenReturn(0L);
        Customer customer = new Customer();
        customer.setId(8L); customer.setUserId(7L); customer.setName("客户");
        customer.setCreatedAt(LocalDateTime.now(AiQuota.ZONE).minusDays(2));
        when(customers.selectOne(any())).thenReturn(customer);
        when(customers.selectById(anyLong())).thenReturn(customer);
        service.dispatchDue();
        verify(wechat, never()).send(any(), any(), any(), any());
        verifyNoInteractions(subscriptions);
        verify(logs, never()).insert(any(com.dealmanager.domain.NotificationSendLog.class));
    }
}
