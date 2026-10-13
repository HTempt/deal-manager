package com.dealmanager.controller;

import com.dealmanager.service.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;

    @PostMapping("/wx-login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.code(), request.nickname(), request.avatarUrl());
    }

    public record LoginRequest(@NotBlank(message = "登录凭证不能为空") String code, String nickname, String avatarUrl) {}
}
