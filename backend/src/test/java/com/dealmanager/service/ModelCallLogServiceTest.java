package com.dealmanager.service;

import com.dealmanager.domain.ModelCallLog;
import com.dealmanager.mapper.ModelCallLogMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Modifier;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelCallLogServiceTest {
    private final ModelCallLogMapper mapper = mock(ModelCallLogMapper.class);
    private final ModelCallLogService service = new ModelCallLogService(mapper);

    @Test
    void saveInsertsProvidedRecordWithoutChangingPayload() {
        ModelCallLog record = new ModelCallLog();
        record.setInvocationId("7b8c3c8a-e779-4256-9f65-10ba44d95e50");
        record.setRequestId("c0dbe774-58bc-4898-bc83-62fd7470acdc");
        record.setUserId(7L);
        record.setEntryPoint("POST /api/ai/advice");
        record.setAction("FOLLOW_ADVICE");
        record.setProvider("test-provider");
        record.setModel("test-model");
        record.setAttempt(1);
        record.setStatus("SUCCESS");
        record.setInputText("{\"model\":\"test-model\",\"messages\":[{\"role\":\"user\",\"content\":\"你好\"}]}");
        record.setOutputText("{\"choices\":[{\"message\":{\"content\":\"原始内容\"}}]}");
        record.setResponseContent("原始内容");
        record.setHttpStatus(200);
        record.setTokenUsage(42);
        record.setStartedAt(LocalDateTime.of(2026, 1, 1, 12, 0));
        record.setFinishedAt(record.getStartedAt().plusSeconds(1));
        record.setDurationMs(1000L);
        String input = record.getInputText();
        String output = record.getOutputText();
        when(mapper.insert(record)).thenReturn(1);

        service.save(record);

        verify(mapper).insert(same(record));
        verifyNoMoreInteractions(mapper);
        assertEquals(input, record.getInputText());
        assertEquals(output, record.getOutputText());
        assertEquals("原始内容", record.getResponseContent());
    }

    @Test
    void saveAcceptsNullableRequestUserAndResponseFields() {
        ModelCallLog record = new ModelCallLog();
        record.setInvocationId("7b8c3c8a-e779-4256-9f65-10ba44d95e50");
        record.setEntryPoint("scheduled-job");
        record.setAttempt(1);
        record.setStatus("FAILED");
        record.setErrorReason("TIMEOUT");
        record.setErrorType("java.net.SocketTimeoutException");
        record.setStartedAt(LocalDateTime.of(2026, 1, 1, 12, 0));
        record.setFinishedAt(record.getStartedAt().plusSeconds(30));
        record.setDurationMs(30000L);

        service.save(record);

        verify(mapper).insert(same(record));
        assertNull(record.getRequestId());
        assertNull(record.getUserId());
        assertNull(record.getAction());
        assertNull(record.getProvider());
        assertNull(record.getModel());
        assertNull(record.getInputText());
        assertNull(record.getOutputText());
        assertNull(record.getResponseContent());
        assertNull(record.getHttpStatus());
        assertNull(record.getTokenUsage());
    }

    @Test
    void insertFailurePropagatesUnchangedToCaller() {
        ModelCallLog record = new ModelCallLog();
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("database unavailable");
        when(mapper.insert(record)).thenThrow(failure);

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class, () -> service.save(record)));

        verify(mapper).insert(same(record));
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void saveIsPublicVoidAndAlwaysStartsIndependentTransaction() throws NoSuchMethodException {
        var method = ModelCallLogService.class.getMethod("save", ModelCallLog.class);
        assertTrue(Modifier.isPublic(method.getModifiers()));
        assertEquals(void.class, method.getReturnType());
        Transactional transaction = method.getAnnotation(Transactional.class);
        assertNotNull(transaction);
        assertEquals(Propagation.REQUIRES_NEW, transaction.propagation());
        assertFalse(transaction.readOnly());
    }
}
