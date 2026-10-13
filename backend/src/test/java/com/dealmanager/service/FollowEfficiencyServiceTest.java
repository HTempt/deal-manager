package com.dealmanager.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.dealmanager.mapper.CustomerMapper;
import com.dealmanager.mapper.DealMapper;
import com.dealmanager.mapper.FollowRecordMapper;
import com.dealmanager.mapper.FollowTaskMapper;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Exercise actual owner-scoped SQL, month bounds and averages without the application datasource. */
class FollowEfficiencyServiceTest {
    private JdbcTemplate jdbc;
    private SqlSession session;
    private FollowEfficiencyService service;
    private final YearMonth month = YearMonth.of(2026, 10);
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 10, 10, 0);
    private long taskId, recordId, dealId;

    @BeforeEach
    void setup() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:efficiency_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE customer(id BIGINT PRIMARY KEY,user_id BIGINT,status VARCHAR(20),created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE follow_task(id BIGINT PRIMARY KEY,user_id BIGINT,customer_id BIGINT,status VARCHAR(20),plan_time TIMESTAMP,completed_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE follow_record(id BIGINT PRIMARY KEY,user_id BIGINT,customer_id BIGINT,created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE deal(id BIGINT PRIMARY KEY,user_id BIGINT,customer_id BIGINT,deal_time TIMESTAMP,amount DECIMAL(12,2),source VARCHAR(30),product_name VARCHAR(100),created_at TIMESTAMP)");
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.addMapper(CustomerMapper.class); config.addMapper(FollowTaskMapper.class);
        config.addMapper(FollowRecordMapper.class); config.addMapper(DealMapper.class);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(config);
        session = factory.getObject().openSession();
        service = new FollowEfficiencyService(session.getMapper(CustomerMapper.class), session.getMapper(FollowTaskMapper.class),
                session.getMapper(FollowRecordMapper.class), session.getMapper(DealMapper.class));
    }

    @AfterEach
    void cleanup() { session.close(); }

    @Test
    void actualMonthStatisticsMatchExampleAndIgnoreCancelledFutureDeletedAndOtherOwners() {
        for (long id = 1; id <= 10; id++) customer(id, 7, id == 10 ? "LOST" : "ACTIVE", now.minusMonths(1));
        customer(11, 7, "DELETED", now.minusMonths(1)); customer(99, 99, "ACTIVE", now.minusMonths(1));
        for (int i = 0; i < 86; i++) task(7, 1, i < 72 ? "COMPLETED" : "PENDING", now.minusDays(2), i < 72 ? now.minusDays(1) : null);
        task(7, 1, "CANCELLED", now.minusDays(2), null);
        task(7, 1, "PENDING", now.plusDays(1), null);
        task(7, 1, "COMPLETED", now.plusDays(1), now); // early completion is still a future plan
        task(7, 1, "PENDING", now.minusMonths(1), null);
        task(7, 11, "PENDING", now.minusDays(1), null);
        task(99, 99, "COMPLETED", now.minusDays(1), now);
        task(7, 99, "PENDING", now.minusDays(1), null); // mismatched customer owner
        for (int i = 0; i < 38; i++) record(7, i % 10 + 1, now.minusDays(1));
        record(7, 1, now.plusMinutes(1)); record(7, 1, now.minusMonths(1));
        record(7, 11, now); record(99, 99, now); record(7, 99, now);

        var result = service.efficiency(7L, month, now);
        assertEquals(86, result.expectedTasks()); assertEquals(72, result.completedTasks());
        assertEquals(14, result.pendingTasks()); assertEquals(new BigDecimal("83.7"), result.completionPercent());
        assertEquals(38, result.followRecords()); assertEquals(10, result.followedCustomers());
        assertEquals(new BigDecimal("3.8"), result.averageFollowCount());
        assertNull(result.averageDealCycleDays()); assertEquals(now, result.asOf());
        assertEquals(1, service.efficiency(99L, month, now).expectedTasks());
    }

    @Test
    void firstDealCycleUsesFractionalDaysAndExcludesRepeatDealsMissingDatesAndInvalidDates() {
        customer(1, 7, "DEAL", LocalDateTime.of(2026, 9, 22, 0, 0));
        customer(2, 7, "DEAL", LocalDateTime.of(2026, 9, 24, 0, 0));
        deal(7, 1, LocalDateTime.of(2026, 10, 4, 9, 36)); deal(7, 1, now.minusDays(1));
        deal(7, 2, LocalDateTime.of(2026, 10, 6, 9, 36));
        customer(3, 7, "DEAL", now.minusMonths(2));
        deal(7, 3, LocalDateTime.of(2026, 9, 30, 23, 59)); deal(7, 3, now.minusDays(1));
        customer(4, 7, "DEAL", now); deal(7, 4, now.minusDays(1));
        customer(5, 7, "DEAL", null); deal(7, 5, now.minusDays(1));
        customer(6, 7, "DELETED", now.minusMonths(1)); deal(7, 6, now.minusDays(1));
        customer(99, 99, "DEAL", now.minusMonths(1)); deal(99, 99, now.minusDays(1));
        var result = service.efficiency(7L, month, now);
        assertEquals(2, result.dealCycleCustomers()); assertEquals(new BigDecimal("12.4"), result.averageDealCycleDays());
    }

    @Test
    void dueTasksUseHalfOpenMonthAndNowBoundsAndIncludeLateCompletionInTheirPlanMonth() {
        customer(1, 7, "ACTIVE", now.minusMonths(2));
        LocalDateTime start = month.atDay(1).atStartOfDay(), end = month.plusMonths(1).atDay(1).atStartOfDay();
        task(7, 1, "COMPLETED", start, end.plusDays(1));
        task(7, 1, "PENDING", end, null);
        task(7, 1, "PENDING", start.minusSeconds(1), null);
        task(7, 1, "PENDING", now, null);
        record(7, 1, start); record(7, 1, start.minusSeconds(1)); record(7, 1, end);
        var current = service.efficiency(7L, month, now);
        assertEquals(2, current.expectedTasks()); assertEquals(0, current.completedTasks());
        assertEquals(1, current.followRecords());
        var historical = service.efficiency(7L, month, end.plusDays(2));
        assertEquals(2, historical.expectedTasks()); assertEquals(1, historical.completedTasks());
        assertEquals(new BigDecimal("50.0"), historical.completionPercent());
    }

    @Test
    void zeroSamplesAreNullWhileRealZeroAndSameDayCycleAreDisplayedAsZero() {
        var empty = service.efficiency(7L, month, now);
        assertNull(empty.completionPercent()); assertNull(empty.averageFollowCount()); assertNull(empty.averageDealCycleDays());
        customer(1, 7, "DEAL", now); task(7, 1, "PENDING", now, null); deal(7, 1, now);
        session.clearCache(); // direct fixture inserts bypass MyBatis's per-session query cache
        var realZero = service.efficiency(7L, month, now);
        assertEquals(new BigDecimal("0.0"), realZero.completionPercent());
        assertEquals(new BigDecimal("0.0"), realZero.averageDealCycleDays());
        var future = service.efficiency(7L, month.plusMonths(1), now);
        assertEquals(0, future.expectedTasks()); assertNull(future.completionPercent());
    }

    @Test
    void overviewAndDealListExcludeDeletedAndMismatchedDealCustomersAndHaveNoGrowthFromZero() {
        customer(1, 7, "DEAL", now); customer(2, 7, "DELETED", now); customer(99, 99, "DEAL", now);
        deal(7, 1, now); deal(7, 2, now); deal(7, 99, now); deal(99, 99, now);
        var data = new DataService(session.getMapper(CustomerMapper.class), session.getMapper(DealMapper.class));
        var result = data.overview(7L, month);
        assertEquals(1, result.newCustomers()); assertEquals(1, result.dealCustomers());
        assertEquals(0, new BigDecimal("100").compareTo(result.dealAmount()));
        assertNull(result.customerGrowthPercent());
        // sources() needs full Customer fields, so validate the same owner-filtered deal query directly.
        assertEquals(1, data.dealList(7L, month).size());
    }

    private void customer(long id, long owner, String status, LocalDateTime created) {
        jdbc.update("INSERT INTO customer VALUES(?,?,?,?)", id, owner, status, created);
    }
    private void task(long owner, long customer, String status, LocalDateTime plan, LocalDateTime completed) {
        jdbc.update("INSERT INTO follow_task VALUES(?,?,?,?,?,?)", ++taskId, owner, customer, status, plan, completed);
    }
    private void record(long owner, long customer, LocalDateTime created) {
        jdbc.update("INSERT INTO follow_record VALUES(?,?,?,?)", ++recordId, owner, customer, created);
    }
    private void deal(long owner, long customer, LocalDateTime at) {
        jdbc.update("INSERT INTO deal VALUES(?,?,?,?,?,?,?,?)", ++dealId, owner, customer, at, new BigDecimal("100"), "微信", "服务", now);
    }
}
