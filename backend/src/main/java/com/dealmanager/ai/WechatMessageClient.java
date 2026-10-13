package com.dealmanager.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.dealmanager.common.LogSafety;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class WechatMessageClient {
    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;
    @Value("${app.wechat.app-id:}") private String appId;
    @Value("${app.wechat.app-secret:}") private String appSecret;

    public boolean configured() { return appId != null && !appId.isBlank() && appSecret != null && !appSecret.isBlank(); }

    public SendResult send(String openid, String templateId, String page, Map<String, String> data) {
        if (!configured()) return new SendResult(false, "微信 AppID/Secret 未配置");
        try {
            RestClient client = restClientBuilder.build();
            JsonNode token = client.get().uri("https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid={appId}&secret={secret}", appId, appSecret)
                    .retrieve().body(JsonNode.class);
            String accessToken = token == null ? null : token.path("access_token").asText(null);
            if (accessToken == null) {
                log.warn("wechat.token.failed errcode={}", token == null ? -1 : token.path("errcode").asInt(-1));
                return new SendResult(false, "获取微信 access_token 失败");
            }
            Map<String, Object> values = data.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> Map.of("value", e.getValue())));
            JsonNode result = client.post().uri("https://api.weixin.qq.com/cgi-bin/message/subscribe/send?access_token={token}", accessToken)
                    .contentType(MediaType.APPLICATION_JSON).body(Map.of("touser", openid, "template_id", templateId, "page", page, "data", values))
                    .retrieve().body(JsonNode.class);
            int errcode = result == null ? -1 : result.path("errcode").asInt(-1);
            if (errcode != 0) log.warn("wechat.message.rejected errcode={}", errcode);
            return errcode == 0 ? new SendResult(true, "ok") : new SendResult(false, result == null ? "微信接口无响应" : result.path("errmsg").asText("发送失败"));
        } catch (Exception e) {
            log.warn("wechat.message.failed errorType={} httpStatus={}", e.getClass().getSimpleName(),
                    e instanceof org.springframework.web.client.RestClientResponseException response ? response.getStatusCode().value() : null);
            log.debug("wechat.message.failure_stack", LogSafety.exception(e));
            return new SendResult(false, "微信消息发送失败");
        }
    }

    public record SendResult(boolean success, String message) {}
}
