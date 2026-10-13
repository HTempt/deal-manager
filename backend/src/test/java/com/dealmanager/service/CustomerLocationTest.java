package com.dealmanager.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dealmanager.common.ApiException;
import com.dealmanager.domain.Customer;
import com.dealmanager.domain.User;
import com.dealmanager.mapper.CustomerMapper;
import com.dealmanager.mapper.FollowRecordMapper;
import com.dealmanager.mapper.FollowTaskMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CustomerLocationTest {
    private CustomerMapper customers;
    private CustomerService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "location-test"), Customer.class);
        customers = mock(CustomerMapper.class);
        SettingsService settings = mock(SettingsService.class);
        User user = new User(); user.setMembershipType("PRO");
        when(settings.user(7L)).thenReturn(user);
        service = new CustomerService(customers, mock(FollowRecordMapper.class), mock(FollowTaskMapper.class), settings);
        when(customers.selectCount(any())).thenReturn(0L);
    }

    @Test
    void createStoresTrimmedCityAndOptionalAddress() {
        Customer created = service.create(7L, new CustomerService.CustomerCommand(
                "王先生", " 杭州市 ", " 西湖区文三路 100 号 ", "", "装修", null,
                "微信", List.of(), "新客户", null, "AUTO", null));

        assertEquals("杭州市", created.getCity());
        assertEquals("西湖区文三路 100 号", created.getAddress());
        verify(customers).insert(created);
    }

    @Test
    void blankCityIsRejected() {
        assertThrows(ApiException.class, () -> service.create(7L, new CustomerService.CustomerCommand(
                "王先生", "  ", null, "", "装修", null,
                "微信", List.of(), "新客户", null, "AUTO", null)));
        verify(customers, never()).insert(any(Customer.class));
    }
}
