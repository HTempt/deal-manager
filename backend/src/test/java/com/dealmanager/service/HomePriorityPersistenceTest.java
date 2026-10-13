package com.dealmanager.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.dealmanager.mapper.FollowRecordMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HomePriorityPersistenceTest {
    @Test
    void actualMapperSelectsLatestFeedbackOncePerActiveOwnedCustomerIncludingTimestampTies() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:home_priority_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE customer(id BIGINT PRIMARY KEY, user_id BIGINT, status VARCHAR(20))");
        jdbc.execute("""
                CREATE TABLE follow_record(id BIGINT PRIMARY KEY, customer_id BIGINT, user_id BIGINT,
                    created_at TIMESTAMP, customer_feedback VARCHAR(30), active_inquiry BOOLEAN)
                """);
        jdbc.update("INSERT INTO customer VALUES(8,7,'ACTIVE'),(9,7,'ACTIVE'),(10,7,'LOST'),"
                + "(11,7,'DEAL'),(12,7,'DELETED'),(13,99,'ACTIVE')");
        LocalDateTime now = LocalDateTime.of(2026, 10, 10, 10, 0);
        insert(jdbc, 1, 8, 7, now.minusDays(1), "有兴趣", true);
        insert(jdbc, 2, 8, 7, now, "考虑中", false);
        insert(jdbc, 3, 8, 7, now, "没有回应", false); // same timestamp: larger id wins
        insert(jdbc, 4, 8, 7, now.plusDays(1), "有兴趣", true); // future record is not evidence
        insert(jdbc, 5, 8, 99, now, "有兴趣", true); // another owner's record cannot suppress it
        insert(jdbc, 6, 10, 7, now, "没有回应", false);
        insert(jdbc, 7, 11, 7, now, "没有回应", false);
        insert(jdbc, 8, 12, 7, now, "没有回应", false);
        insert(jdbc, 9, 13, 99, now, "有兴趣", true);
        insert(jdbc, 10, 9, 7, now.minusDays(31), "没有回应", false); // no arbitrary history cutoff

        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.addMapper(FollowRecordMapper.class);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(config);
        try (var session = factory.getObject().openSession()) {
            var mapper = session.getMapper(FollowRecordMapper.class);
            var records = mapper.selectLatestForActiveCustomers(7L, now);
            assertEquals(2, records.size());
            var latest = records.stream().filter(r -> r.getCustomerId().equals(8L)).findFirst().orElseThrow();
            assertEquals(3L, latest.getId());
            assertEquals("没有回应", latest.getCustomerFeedback());
            assertEquals(Boolean.FALSE, latest.getActiveInquiry());
            assertEquals(now, latest.getCreatedAt());
            assertEquals(10L, records.stream().filter(r -> r.getCustomerId().equals(9L)).findFirst().orElseThrow().getId());
            assertEquals(1, mapper.selectLatestForActiveCustomers(99L, now).size());
            assertTrue(mapper.selectLatestForActiveCustomers(123L, now).isEmpty());
        }
    }

    private void insert(JdbcTemplate jdbc, long id, long customer, long owner, LocalDateTime at, String feedback, boolean inquiry) {
        jdbc.update("INSERT INTO follow_record VALUES(?,?,?,?,?,?)", id, customer, owner, at, feedback, inquiry);
    }
}
