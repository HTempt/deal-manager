package com.dealmanager.config;

import com.dealmanager.testutil.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RequestLoggingFilterTest {
    @AfterEach void clearContext() { MDC.clear(); }

    @Test
    void correlatesRequestAndUserWithoutLoggingBodiesHeadersOrQueryAndRestoresMdc() throws Exception {
        MDC.setContextMap(Map.of("requestId", "outer", "userId", "outer-user"));
        var request = new MockHttpServletRequest("POST", "/api/customers/8/summary");
        request.setQueryString("secret=query-secret");
        request.addHeader("Authorization", "Bearer token-secret");
        request.addHeader("X-Request-Id", "untrusted\ninjected-id");
        request.setContent("private-phone-and-follow-feedback".getBytes());
        var response = new MockHttpServletResponse();
        try (var logs = new LogCapture(RequestLoggingFilter.class)) {
            new RequestLoggingFilter(3000).doFilter(request, response, (req, res) -> {
                assertNull(MDC.get("userId"));
                MDC.put("userId", "7");
                req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/customers/{id}/summary");
                res.getWriter().write("private-response");
            });
            String requestId = response.getHeader("X-Request-Id");
            assertNotNull(UUID.fromString(requestId));
            assertEquals(2, logs.events().size());
            assertTrue(logs.output().contains("status=200 durationMs="));
            assertTrue(logs.output().contains("path=/api/customers/{id}/summary"));
            assertEquals(requestId, logs.events().get(0).getMDCPropertyMap().get("requestId"));
            assertEquals(requestId, logs.events().get(1).getMDCPropertyMap().get("requestId"));
            assertEquals("7", logs.events().get(1).getMDCPropertyMap().get("userId"));
            for (String secret : new String[]{"query-secret", "token-secret", "injected-id", "private-phone", "private-response"}) {
                assertFalse(logs.output().contains(secret));
            }
        }
        assertEquals(Map.of("requestId", "outer", "userId", "outer-user"), MDC.getCopyOfContextMap());
    }

    @Test
    void logsUnauthorizedAndHandledServerErrorsWithTheirActualStatus() throws Exception {
        for (int status : new int[]{401, 500}) {
            var request = new MockHttpServletRequest("GET", "/api/customers");
            var response = new MockHttpServletResponse();
            try (var logs = new LogCapture(RequestLoggingFilter.class)) {
                new RequestLoggingFilter(3000).doFilter(request, response, (req, res) -> response.setStatus(status));
                assertTrue(logs.output().contains("status=" + status));
                assertEquals(status == 500 ? "ERROR" : "WARN", logs.events().get(1).getLevel().toString());
            }
            assertNull(MDC.get("requestId"));
        }
    }

    @Test
    void logsSlowRequestsAndUnhandledFailureWithoutLeakingExceptionMessage() throws Exception {
        try (var logs = new LogCapture(RequestLoggingFilter.class)) {
            var request = new MockHttpServletRequest("GET", "/api/customers");
            var response = new MockHttpServletResponse();
            new RequestLoggingFilter(0).doFilter(request, response, (req, res) -> {});
            assertTrue(logs.output().contains("slow=true"));
            RuntimeException failure = new RuntimeException("private-secret-message");
            assertSame(failure, assertThrows(RuntimeException.class, () -> new RequestLoggingFilter(3000)
                    .doFilter(new MockHttpServletRequest("GET", "/api/customers"), new MockHttpServletResponse(), (req, res) -> { throw failure; })));
            assertTrue(logs.output().contains("http.request.unhandled"));
            assertTrue(logs.output().contains("status=500"));
            assertFalse(logs.output().contains("private-secret-message"));
            assertNull(MDC.get("requestId"));
        }
    }
}
