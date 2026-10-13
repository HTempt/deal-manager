package com.dealmanager.ai;

import com.dealmanager.common.ApiException;
import com.dealmanager.domain.ModelCallLog;
import com.dealmanager.service.ModelCallLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelCallLoggingTest {
    private HttpServer server;
    private final ModelCallLogService logs = mock(ModelCallLogService.class);
    private final ObjectMapper json = new ObjectMapper();

    @AfterEach void cleanup() {
        if (server != null) server.stop(0);
        MDC.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    private ModelPoolClient client(String... names) {
        AiModelProperties p = new AiModelProperties();
        p.setPool(java.util.Arrays.stream(names).map(name -> {
            var model = new AiModelProperties.Model();
            model.setId(name); model.setModel(name); model.setApiKey("secret-key");
            model.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            return model;
        }).toList());
        return new ModelPoolClient(p, RestClient.builder(), logs, json);
    }

    private AtomicInteger start(int firstStatus, String firstBody, String nextBody) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            boolean first = calls.incrementAndGet() == 1;
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = (first ? firstBody : nextBody).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(first ? firstStatus : 200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start(); return calls;
    }

    private String success(String content) throws Exception {
        return json.writeValueAsString(java.util.Map.of("choices", List.of(java.util.Map.of("message", java.util.Map.of("content", content), "finish_reason", "stop")), "usage", java.util.Map.of("total_tokens", 12)));
    }
    private List<ModelCallLog> records(int count) {
        var captor = ArgumentCaptor.forClass(ModelCallLog.class);
        verify(logs, times(count)).save(captor.capture()); return captor.getAllValues();
    }

    @Test void successCapturesActualInputOutputAndRequestContext() throws Exception {
        String raw = success("中文输出");
        start(200, raw, raw);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/customers/42/summary");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/customers/{id}/summary");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        MDC.put("userId", "7"); MDC.put("requestId", "request-123");
        assertEquals("中文输出", client("one").generate("中文输入", "SUMMARY").content());
        var record = records(1).get(0);
        assertEquals("SUCCESS", record.getStatus()); assertEquals(200, record.getHttpStatus());
        assertEquals(12, record.getTokenUsage()); assertEquals(raw, record.getOutputText());
        assertEquals("中文输出", record.getResponseContent());
        assertTrue(json.readTree(record.getInputText()).get("messages").get(1).get("content").asText().contains("中文输入"));
        assertTrue(record.getInputText().contains("system")); assertFalse(record.getInputText().contains("secret-key"));
        assertEquals("POST /api/customers/{id}/summary", record.getEntryPoint());
        assertEquals(7L, record.getUserId()); assertEquals("request-123", record.getRequestId());
        assertEquals(1, record.getAttempt()); assertEquals("one", record.getProvider());
        assertNotNull(record.getStartedAt()); assertNotNull(record.getFinishedAt()); assertTrue(record.getDurationMs() >= 0);
    }

    @Test void failedAttemptAndBackupAreSeparateRowsWithSameInvocation() throws Exception {
        start(429, "vendor error", success("备用成功"));
        assertEquals("备用成功", client("first", "second").generate("输入", "SUMMARY").content());
        var rows = records(2);
        assertEquals(rows.get(0).getInvocationId(), rows.get(1).getInvocationId());
        assertEquals("FAILED", rows.get(0).getStatus()); assertEquals("HTTP_429", rows.get(0).getErrorReason());
        assertEquals("vendor error", rows.get(0).getOutputText()); assertEquals(429, rows.get(0).getHttpStatus());
        assertEquals(1, rows.get(0).getAttempt()); assertEquals(2, rows.get(1).getAttempt());
        assertEquals("SUCCESS", rows.get(1).getStatus());
    }

    @Test void invalidResponseIsStillStoredWithTokensAndContent() throws Exception {
        String raw = success("不完整建议"); start(200, raw, raw);
        assertThrows(ApiException.class, () -> client("one").generate("资料", "CUSTOMER_SUMMARY"));
        var row = records(1).get(0);
        assertEquals("FAILED", row.getStatus()); assertEquals("INVALID_RESPONSE", row.getErrorReason());
        assertEquals(raw, row.getOutputText()); assertEquals("不完整建议", row.getResponseContent()); assertEquals(12, row.getTokenUsage());
    }

    @Test void exhaustedModelsAreAllRecorded() throws Exception {
        start(500, "first failure", "{}");
        assertThrows(ApiException.class, () -> client("one", "two").generate("输入", "SUMMARY"));
        assertTrue(records(2).stream().allMatch(row -> "FAILED".equals(row.getStatus())));
    }

    @Test void noConfiguredModelCreatesDiagnosticRow() {
        assertThrows(ApiException.class, () -> new ModelPoolClient(new AiModelProperties(), RestClient.builder(), logs, json).generate("输入", null));
        var row = records(1).get(0);
        assertEquals(0, row.getAttempt()); assertEquals("NO_CONFIGURED_MODEL", row.getErrorReason());
        assertEquals("输入", row.getInputText()); assertEquals("INTERNAL", row.getEntryPoint());
    }

    @Test void databaseFailureDoesNotRepeatPaidCallOrHideModelResult() throws Exception {
        String body = success("完成"); var calls = start(200, body, body);
        doThrow(new IllegalStateException("database unavailable")).when(logs).save(any());
        assertEquals("完成", client("one", "two").generate("输入", "SUMMARY").content());
        assertEquals(1, calls.get()); records(1);
    }

    @Test void malformedJsonRawOutputIsRetained() throws Exception {
        start(200, "not json", "not json");
        assertThrows(ApiException.class, () -> client("one").generate("输入", "SUMMARY"));
        var row = records(1).get(0); assertEquals("not json", row.getOutputText());
        assertEquals("INVALID_RESPONSE", row.getErrorReason());
    }
}
