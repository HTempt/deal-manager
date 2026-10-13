package com.dealmanager.controller;

import com.dealmanager.domain.FollowRecord;
import com.dealmanager.service.FollowService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/follows")
@RequiredArgsConstructor
public class FollowController {
    private final FollowService followService;

    @GetMapping("/today")
    public List<FollowService.TaskView> today(@AuthenticationPrincipal Long userId) {
        return followService.today(userId);
    }

    @GetMapping("/overdue")
    public List<FollowService.TaskView> overdue(@AuthenticationPrincipal Long userId) {
        return followService.overdue(userId);
    }

    @GetMapping("/upcoming")
    public List<FollowService.TaskView> upcoming(@AuthenticationPrincipal Long userId) {
        return followService.upcoming(userId);
    }

    @PostMapping
    public FollowRecord create(@AuthenticationPrincipal Long userId, @Valid @RequestBody FollowRequest request) {
        return followService.create(userId, request.toCommand());
    }

    @PostMapping("/{id}/complete")
    public void complete(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        followService.complete(userId, id);
    }

    public record FollowRequest(@NotNull(message = "请选择客户") Long customerId, Long taskId, String followType,
                                String content, String customerFeedback, String customerStatus,
                                Integer intentionChange, Boolean activeInquiry, Boolean priceInquiry,
                                Integer intentionScore, String intentionMode, LocalDateTime nextFollowTime) {
        FollowService.FollowCommand toCommand() {
            return new FollowService.FollowCommand(customerId, taskId, followType, content, customerFeedback,
                    customerStatus, intentionChange, activeInquiry, priceInquiry, intentionScore, intentionMode, nextFollowTime);
        }
    }
}
