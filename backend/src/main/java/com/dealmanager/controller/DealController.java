package com.dealmanager.controller;

import com.dealmanager.domain.Deal;
import com.dealmanager.service.DealService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/deals")
@RequiredArgsConstructor
public class DealController {
    private final DealService dealService;

    @PostMapping
    public Deal create(@AuthenticationPrincipal Long userId, @Valid @RequestBody DealRequest request) {
        return dealService.create(userId, request.customerId(), request.amount(), request.productName(), request.dealTime());
    }

    public record DealRequest(@NotNull Long customerId,
                              @NotNull @DecimalMin(value = "0.01", message = "成交金额必须大于0") BigDecimal amount,
                              String productName, LocalDateTime dealTime) {}
}
