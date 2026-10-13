package com.dealmanager.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dealmanager.common.ApiException;
import com.dealmanager.config.JwtService;
import com.dealmanager.domain.User;
import com.dealmanager.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {
    private final UserMapper userMapper;
    private final JwtService jwtService;
    private final RestClient.Builder restClientBuilder;

    @Value("${app.wechat.app-id}") private String appId;
    @Value("${app.wechat.app-secret}") private String appSecret;

    public Map<String, Object> login(String code, String nickname, String avatarUrl) {
        String openid = exchangeOpenid(code);
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getOpenid, openid));
        boolean newUser = user == null;
        LocalDateTime now = LocalDateTime.now();
        if (user == null) {
            user = new User();
            user.setOpenid(openid);
            user.setNickname(StringUtils.hasText(nickname) ? nickname : "老板");
            user.setAvatarUrl(avatarUrl);
            user.setMembershipType("FREE");
            user.setCreatedAt(now);
            user.setUpdatedAt(now);
            userMapper.insert(user);
        } else if (StringUtils.hasText(nickname) || StringUtils.hasText(avatarUrl)) {
            if (StringUtils.hasText(nickname)) user.setNickname(nickname);
            if (StringUtils.hasText(avatarUrl)) user.setAvatarUrl(avatarUrl);
            user.setUpdatedAt(now);
            userMapper.updateById(user);
        }
        MDC.put("userId", user.getId().toString());
        log.info("auth.login.completed userId={} newUser={} developmentMode={}", user.getId(), newUser,
                !StringUtils.hasText(appId) || !StringUtils.hasText(appSecret));
        return Map.of("token", jwtService.create(user.getId()), "user", user);
    }

    @SuppressWarnings("unchecked")
    private String exchangeOpenid(String code) {
        if (!StringUtils.hasText(code)) throw ApiException.badRequest("缺少微信登录凭证");
        if (!StringUtils.hasText(appId) || !StringUtils.hasText(appSecret)) {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
                return "dev_" + HexFormat.of().formatHex(digest, 0, 16);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        Map<String, Object> result = restClientBuilder.build().get()
                .uri("https://api.weixin.qq.com/sns/jscode2session?appid={appid}&secret={secret}&js_code={code}&grant_type=authorization_code",
                        appId, appSecret, code).retrieve().body(Map.class);
        if (result == null || result.get("openid") == null) {
            log.warn("auth.wechat.rejected errcode={}", result != null && result.get("errcode") instanceof Number number ? number.intValue() : -1);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "微信登录失败，请重试");
        }
        return result.get("openid").toString();
    }
}
