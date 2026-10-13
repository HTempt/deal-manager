package com.dealmanager.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.dealmanager.domain.ModelCallLog;
import com.dealmanager.mapper.ModelCallLogMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real SQL and transaction integration without Boot or application datasource configuration. */
@SpringJUnitConfig(ModelCallLogPersistenceTest.TestConfiguration.class)
class ModelCallLogPersistenceTest {
    @Autowired private ModelCallLogService service;
    @Autowired private ModelCallLogMapper mapper;
    @Autowired private DataSource dataSource;
    @Autowired private DataSourceTransactionManager transactionManager;

    @BeforeEach
    void clearRecords() {
        new JdbcTemplate(dataSource).update("DELETE FROM model_call_log");
    }

    @Test
    void insertsAndReadsCompleteChineseRequestAndResponse() {
        ModelCallLog record = newRecord();
        record.setRequestId(UUID.randomUUID().toString());
        record.setUserId(7L);
        record.setAction("FOLLOW_ADVICE");
        record.setProvider("测试供应商");
        record.setModel("test-model");
        // Exceed ordinary VARCHAR limits to catch truncation of full request/response bodies.
        String input = "{\"model\":\"test-model\",\"messages\":[{\"role\":\"user\",\"content\":\""
                + "你好，请分析客户的完整跟进记录。\\n".repeat(1000) + "\"}]}";
        String output = "{\"choices\":[{\"message\":{\"content\":\""
                + "建议明天联系客户，确认采购需求。\\n".repeat(1000)
                + "\"}}],\"usage\":{\"total_tokens\":42}}";
        record.setInputText(input);
        record.setOutputText(output);
        record.setResponseContent("建议明天联系客户，确认采购需求。");
        record.setHttpStatus(200);
        record.setTokenUsage(42);

        service.save(record);

        assertNotNull(record.getId(), "MyBatis Plus must populate the database-generated id");
        ModelCallLog stored = mapper.selectById(record.getId());
        assertNotNull(stored);
        assertEquals(record, stored, "Every mapped field must survive the actual database round trip");
        assertEquals(input, stored.getInputText());
        assertEquals(output, stored.getOutputText());
        assertEquals(1L, mapper.selectCount(null));
    }

    @Test
    void persistsFailureWithNoConfiguredProviderModelOrAction() {
        ModelCallLog record = newRecord();
        record.setStatus("FAILED");
        record.setErrorReason("NO_MODEL_CONFIGURED");
        record.setErrorType("java.lang.IllegalStateException");

        service.save(record);

        ModelCallLog stored = mapper.selectById(record.getId());
        assertNotNull(stored);
        assertNull(stored.getProvider());
        assertNull(stored.getModel());
        assertNull(stored.getAction());
        assertNull(stored.getRequestId());
        assertNull(stored.getUserId());
        assertNull(stored.getInputText());
        assertNull(stored.getOutputText());
        assertNull(stored.getResponseContent());
        assertNull(stored.getHttpStatus());
        assertNull(stored.getTokenUsage());
        assertEquals(record, stored);
    }

    @Test
    void requiresNewLogRemainsCommittedWhenOuterTransactionRollsBack() {
        ModelCallLog outerRecord = newRecord();
        ModelCallLog independentRecord = newRecord();
        independentRecord.setInputText("外层业务事务最终回滚");
        independentRecord.setOutputText("独立日志事务仍然提交");
        TransactionTemplate outerTransaction = new TransactionTemplate(transactionManager);
        IllegalStateException rollback = new IllegalStateException("roll back business transaction");

        assertSame(rollback, assertThrows(IllegalStateException.class, () ->
                outerTransaction.executeWithoutResult(status -> {
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                    // The real mapper joins the outer transaction, unlike the proxied service.
                    mapper.insert(outerRecord);
                    service.save(independentRecord);
                    assertNotNull(mapper.selectById(outerRecord.getId()));
                    assertNotNull(mapper.selectById(independentRecord.getId()));
                    throw rollback;
                })));

        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertNull(mapper.selectById(outerRecord.getId()), "The outer insert must really roll back");
        assertEquals(independentRecord, mapper.selectById(independentRecord.getId()),
                "REQUIRES_NEW must commit independently of the outer transaction");
        assertEquals(1L, mapper.selectCount(null));
    }

    private static ModelCallLog newRecord() {
        ModelCallLog record = new ModelCallLog();
        record.setInvocationId(UUID.randomUUID().toString());
        record.setEntryPoint("POST /api/ai/advice");
        record.setAttempt(1);
        record.setStatus("SUCCESS");
        record.setStartedAt(LocalDateTime.of(2026, 1, 1, 12, 0, 0, 123456000));
        record.setFinishedAt(record.getStartedAt().plusSeconds(1));
        record.setDurationMs(1000L);
        return record;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(ModelCallLogService.class)
    static class TestConfiguration {
        @Bean
        DataSource dataSource() {
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName("org.h2.Driver");
            dataSource.setUrl("jdbc:h2:mem:model_call_log_" + UUID.randomUUID()
                    + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
            dataSource.setUsername("sa");
            dataSource.setPassword("");
            ResourceDatabasePopulator migration = new ResourceDatabasePopulator(
                    new ClassPathResource("db/migration/V10__model_call_log.sql"));
            migration.setSqlScriptEncoding("UTF-8");
            migration.execute(dataSource);
            return dataSource;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            return factory.getObject();
        }

        @Bean
        MapperFactoryBean<ModelCallLogMapper> modelCallLogMapper(SqlSessionFactory sqlSessionFactory) {
            MapperFactoryBean<ModelCallLogMapper> factory = new MapperFactoryBean<>(ModelCallLogMapper.class);
            factory.setSqlSessionFactory(sqlSessionFactory);
            return factory;
        }

        @Bean
        DataSourceTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}
