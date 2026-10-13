package com.dealmanager.ai;

import com.dealmanager.common.ApiException;
import com.dealmanager.testutil.LogCapture;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ModelPoolClientTest {
    private HttpServer server;

    @AfterEach
    void stop() { if (server != null) server.stop(0); }

    @Test
    void firstModelSuccessReturnsContentAndUsage() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        start(exchange -> { calls.incrementAndGet(); respond(exchange, 200, response("你好", "qwen", 12, "stop")); });
        AiModelProperties p = props(model("one", "qwen", true));
        ModelPoolClient.ModelResult result = client(p).generate("客户提示", "GENERATE");
        assertEquals("你好", result.content());
        assertEquals("qwen", result.model());
        assertEquals(12, result.tokenUsage());
        assertEquals(1, calls.get());
    }

    @Test
    void summaryUsesAnalysisPromptAndRealStatsRatherThanSalesScriptInstructions() throws Exception {
        start(exchange -> {
            var body = new ObjectMapper().readTree(exchange.getRequestBody());
            String system = body.get("messages").get(0).get("content").asText();
            String user = body.get("messages").get(1).get("content").asText();
            assertTrue(system.contains("经营分析助手"));
            assertFalse(system.contains("可直接发送"));
            assertTrue(user.contains("经营分析"));
            assertTrue(user.contains("newCustomers"));
            assertTrue(system.contains("globalAiSettings"));
            assertTrue(system.contains("replyLength控制详略"));
            assertFalse(system.contains("120至200字"));
            assertEquals(0.3, body.get("temperature").asDouble());
            respond(exchange, 200, response("月度经营分析", "qwen", 12, "stop"));
        });
        assertEquals("月度经营分析", client(props(model("one", "qwen", true)))
                .generate("{\"newCustomers\":5}", "SUMMARY").content());
    }

    @Test
    void quota429FallsThroughToNextModel() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        start(exchange -> { if (calls.incrementAndGet() == 1) respond(exchange, 429, "vendor-private-secret"); else respond(exchange, 200, response("备用", "second", 3, "stop")); });
        AiModelProperties p = props(model("one", "first", true), model("two", "second", true));
        try (var logs = new LogCapture(ModelPoolClient.class)) {
            ModelPoolClient.ModelResult result = client(p).generate("private-customer-feedback", "SHORTEN");
            assertEquals("备用", result.content());
            assertEquals(2, calls.get());
            assertTrue(logs.output().contains("reason=HTTP_429"));
            assertTrue(logs.output().contains("hasBackup=true"));
            assertTrue(logs.output().contains("model=second tokens=3"));
            assertTrue(logs.output().contains("durationMs="));
            assertFalse(logs.output().contains("vendor-private-secret"));
            assertFalse(logs.output().contains("private-customer-feedback"));
            assertFalse(logs.output().contains("test-key"));
        }
    }

    @Test
    void emptyContentIsRejected() throws Exception {
        start(exchange -> respond(exchange, 200, response("", "qwen", 1, "stop")));
        ApiException ex = assertThrows(ApiException.class, () -> client(props(model("one", "qwen", true))).generate("提示", null));
        assertEquals(502, ex.getStatus().value());
    }

    @Test
    void everyAdviceRouteFailsWhenAllProvidersFailWithoutTemplateFallback() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        start(exchange -> { calls.incrementAndGet(); respond(exchange, 503, "{}"); });
        var client = client(props(model("one", "first", true), model("two", "second", true)));
        for (String action : List.of("CUSTOMER_SUMMARY", "SUMMARY", "DAILY_ADVICE", "ASSISTANT_GENERATE", "ASSISTANT_REGENERATE", "ASSISTANT_SHORTEN")) {
            ApiException error = assertThrows(ApiException.class, () -> client.generate("业务资料", action));
            assertEquals(502, error.getStatus().value());
        }
        assertEquals(12, calls.get());
    }

    @Test
    void missingKeyReturns503WithoutCallingServer() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        start(exchange -> calls.incrementAndGet());
        AiModelProperties.Model m = model("one", "qwen", true); m.setApiKey("");
        ApiException ex = assertThrows(ApiException.class, () -> client(props(m)).generate("提示", null));
        assertEquals(503, ex.getStatus().value());
        assertEquals(0, calls.get());
        assertFalse(ex.getMessage().contains("secret"));
    }

    private ModelPoolClient client(AiModelProperties properties) { return new ModelPoolClient(properties, RestClient.builder(), org.mockito.Mockito.mock(com.dealmanager.service.ModelCallLogService.class), new ObjectMapper()); }

    @Test
    void assistantUsesStructuredAdviceInstructionsAndLargerTokenBudget() throws Exception {
        String advice = "{\"assessment\":\"预算有顾虑\",\"suggestedAction\":\"询问项目范围\",\"content\":\"您主要顾虑哪些项目？\",\"followUpDays\":2,\"followUpReason\":\"留出考虑时间\"}";
        start(exchange -> {
            var body = new ObjectMapper().readTree(exchange.getRequestBody());
            String system = body.get("messages").get(0).get("content").asText();
            assertTrue(system.contains("销售跟进助手"));
            assertTrue(system.contains("followUpDays"));
            assertTrue(system.contains("globalAiSettings"));
            assertTrue(system.contains("包括判断、动作、摘要、经营分析与推荐话术"));
            assertEquals(1200, body.get("max_tokens").asInt());
            respond(exchange, 200, response(advice, "qwen", 50, "stop"));
        });
        assertEquals(2, FollowAdvice.parse(client(props(model("one", "qwen", true)))
                .generate("客户反馈", "ASSISTANT_GENERATE").content()).followUpDays());
    }

    @Test
    void invalidStructuredResponseFallsThroughToBackup() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String advice = "{\"assessment\":\"信息不足\",\"suggestedAction\":\"确认需求\",\"content\":\"请问具体需求？\",\"followUpDays\":1,\"followUpReason\":\"及时了解需求\"}";
        start(exchange -> respond(exchange, 200, response(calls.incrementAndGet() == 1 ? "只有话术" : advice, "m", 30, "stop")));
        var result = client(props(model("one", "first", true), model("two", "second", true)))
                .generate("客户反馈", "ASSISTANT_GENERATE");
        assertEquals(2, calls.get());
        assertEquals("second", result.model());
    }
    @Test
    void customerSummaryUsesFactualAnalysisPromptAndSeparateTokenBudget() throws Exception {
        String brief = "{\"overview\":\"预算15万元，报价中\",\"suggestion\":\"先确认预算顾虑\"}";
        start(exchange -> {
            var body = new ObjectMapper().readTree(exchange.getRequestBody());
            String system = body.get("messages").get(0).get("content").asText();
            assertTrue(system.contains("客户跟进分析助手"));
            assertTrue(system.contains("不编造家人意见"));
            assertTrue(system.contains("已成交客户优先服务维护"));
            assertFalse(system.contains("可直接发送"));
            assertTrue(system.contains("globalAiSettings"));
            assertTrue(system.contains("全局语气和长度偏好"));
            assertTrue(system.contains("客户职业未明确记录时不描述其职业"));
            assertTrue(system.contains("不从销售者业务推断客户需求"));
            assertTrue(system.contains("客户摘要overview只能基于customerFacts及其history概括"));
            assertTrue(body.get("messages").get(1).get("content").asText().contains("客户情况与跟进历史"));
            assertEquals(0.3, body.get("temperature").asDouble());
            assertEquals(1000, body.get("max_tokens").asInt());
            respond(exchange, 200, response(brief, "qwen", 40, "stop"));
        });
        var result = client(props(model("one", "qwen", true))).generate("客户资料", "CUSTOMER_SUMMARY");
        assertEquals("先确认预算顾虑", CustomerBrief.parse(result.content()).suggestion());
    }

    @Test
    void malformedCustomerSummaryFallsThroughToBackupModel() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String brief = "{\"overview\":\"关注预算\",\"suggestion\":\"确认项目范围\"}";
        start(exchange -> respond(exchange, 200, response(calls.incrementAndGet() == 1
                ? "{\"overview\":\"缺少建议\"}" : brief, "m", 30, "stop")));
        var result = client(props(model("one", "first", true), model("two", "second", true)))
                .generate("客户资料", "CUSTOMER_SUMMARY");
        assertEquals(2, calls.get()); assertEquals("second", result.model());
    }

    @Test
    void dailyAdviceUsesPlanningPromptAndAcceptanceIsResolvedByTheBusinessLayer() throws Exception {
        String plan = "{\"overview\":\"今天先联系报价未回复的客户\",\"items\":[{\"customerId\":8,\"priority\":\"S\",\"advice\":\"报价 ¥38,000，3 天未回复，建议今天下午电话联系\"}]}";
        AtomicInteger calls = new AtomicInteger();
        start(exchange -> {
            var body = new ObjectMapper().readTree(exchange.getRequestBody());
            String system = body.get("messages").get(0).get("content").asText();
            assertTrue(system.contains("销售工作规划助手"));
            assertTrue(system.contains("priority"));
            assertTrue(system.contains("globalAiSettings"));
            assertFalse(system.contains("可直接发送的一段中文话术"));
            assertTrue(body.get("messages").get(1).get("content").asText().contains("重点跟进建议"));
            assertEquals(0.3, body.get("temperature").asDouble());
            assertEquals(1200, body.get("max_tokens").asInt());
            calls.incrementAndGet();
            respond(exchange, 200, response(plan, "qwen", 60, "stop"));
        });
        var result = client(props(model("one", "qwen", true))).generate("候选客户", "DAILY_ADVICE");
        // 结构有效但客户不在候选池内时由业务层拒绝，模型池不擅自切换备用模型。
        assertEquals(1, calls.get());
        assertEquals("S", DailyPlan.parse(result.content(), java.util.Set.of(8L), 1).items().get(0).priority());
        assertThrows(IllegalArgumentException.class, () -> DailyPlan.parse(result.content(), java.util.Set.of(9L), 1));
    }

    @Test
    void malformedDailyAdviceFallsThroughToBackupModel() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String plan = "{\"overview\":\"先联系报价未回复的客户\",\"items\":[{\"customerId\":8,\"priority\":\"S\",\"advice\":\"建议今天下午电话联系\"}]}";
        start(exchange -> respond(exchange, 200, response(calls.incrementAndGet() == 1
                ? "{\"overview\":\"缺少建议列表\"}" : plan, "m", 30, "stop")));
        var result = client(props(model("one", "first", true), model("two", "second", true)))
                .generate("候选客户", "DAILY_ADVICE");
        assertEquals(2, calls.get());
        assertEquals("second", result.model());
    }

    private AiModelProperties props(AiModelProperties.Model... models) { AiModelProperties p = new AiModelProperties(); p.setPool(List.of(models)); return p; }
    private AiModelProperties.Model model(String id, String name, boolean enabled) { AiModelProperties.Model m = new AiModelProperties.Model(); m.setId(id); m.setModel(name); m.setApiKey("test-key"); m.setBaseUrl(baseUrl()); m.setEnabled(enabled); return m; }
    private String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    private void start(Handler handler) throws IOException { server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.createContext("/chat/completions", handler::handle); server.start(); }
    private interface Handler { void handle(HttpExchange exchange) throws IOException; }
    private static void respond(HttpExchange e, int status, String body) throws IOException { byte[] bytes = body.getBytes(StandardCharsets.UTF_8); e.getResponseHeaders().set("Content-Type", "application/json"); e.sendResponseHeaders(status, bytes.length); try (var out = e.getResponseBody()) { out.write(bytes); } }
    private static String response(String content, String model, int tokens, String finish) {
        try { return new ObjectMapper().writeValueAsString(java.util.Map.of("choices", List.of(java.util.Map.of("finish_reason", finish, "message", java.util.Map.of("content", content))), "usage", java.util.Map.of("total_tokens", tokens), "model", model)); }
        catch (Exception e) { throw new AssertionError(e); }
    }
}
