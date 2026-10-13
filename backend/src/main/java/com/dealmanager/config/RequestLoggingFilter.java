package com.dealmanager.config;

import com.dealmanager.common.LogSafety;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {
    private final long slowRequestMs;

    public RequestLoggingFilter(@Value("${app.logging.slow-request-ms:3000}") long slowRequestMs) {
        this.slowRequestMs = slowRequestMs;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Map<String, String> previousContext = MDC.getCopyOfContextMap();
        String requestId = UUID.randomUUID().toString();
        MDC.put("requestId", requestId);
        MDC.remove("userId");
        response.setHeader("X-Request-Id", requestId);
        long started = System.nanoTime();
        boolean failed = false;
        // Query strings, headers, request bodies and response bodies are deliberately excluded.
        log.info("http.request.started method={} path={}", LogSafety.text(request.getMethod()), LogSafety.text(request.getRequestURI()));
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException e) {
            failed = true;
            log.error("http.request.unhandled errorType={}", e.getClass().getSimpleName(), LogSafety.exception(e));
            throw e;
        } finally {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            int status = failed ? 500 : response.getStatus();
            Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            String path = LogSafety.text(route == null ? request.getRequestURI() : route.toString());
            if (status >= 500) log.error("http.request.completed method={} path={} status={} durationMs={}", request.getMethod(), path, status, durationMs);
            else if (status >= 400 || durationMs >= slowRequestMs) log.warn("http.request.completed method={} path={} status={} durationMs={} slow={}", request.getMethod(), path, status, durationMs, durationMs >= slowRequestMs);
            else log.info("http.request.completed method={} path={} status={} durationMs={}", request.getMethod(), path, status, durationMs);
            MDC.clear();
            if (previousContext != null) MDC.setContextMap(previousContext);
        }
    }
}
