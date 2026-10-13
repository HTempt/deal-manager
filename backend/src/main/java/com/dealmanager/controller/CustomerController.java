package com.dealmanager.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dealmanager.domain.Customer;
import com.dealmanager.domain.FollowRecord;
import com.dealmanager.service.CustomerService;
import com.dealmanager.service.CustomerSummaryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {
    private final CustomerService customerService;
    private final CustomerSummaryService customerSummaryService;

    @GetMapping
    public Page<Customer> list(@AuthenticationPrincipal Long userId,
                               @RequestParam(required = false) String keyword,
                               @RequestParam(required = false, defaultValue = "ALL") String filter,
                               @RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "20") int size) {
        return customerService.list(userId, keyword, filter, page, size);
    }

    @PostMapping
    public Customer create(@AuthenticationPrincipal Long userId, @Valid @RequestBody CustomerRequest request) {
        return customerService.create(userId, request.toCommand());
    }

    @GetMapping("/{id}")
    public Customer get(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        return customerService.get(userId, id);
    }

    @GetMapping("/{id}/follows")
    public List<FollowRecord> records(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        return customerService.records(userId, id);
    }

    @PostMapping("/{id}/summary")
    public CustomerSummaryService.Summary summary(@AuthenticationPrincipal Long userId, @PathVariable Long id,
                                                 @RequestParam(defaultValue = "false") boolean refresh) {
        return customerSummaryService.summarize(userId, id, refresh);
    }

    @PutMapping("/{id}")
    public Customer update(@AuthenticationPrincipal Long userId, @PathVariable Long id,
                           @Valid @RequestBody CustomerRequest request) {
        return customerService.update(userId, id, request.toCommand());
    }

    @DeleteMapping("/{id}")
    public void delete(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        customerService.delete(userId, id);
    }

    @PutMapping("/{id}/next-follow")
    public Customer schedule(@AuthenticationPrincipal Long userId, @PathVariable Long id,
                             @Valid @RequestBody ScheduleRequest request) {
        return customerService.schedule(userId, id, request.nextFollowTime());
    }

    public record ScheduleRequest(@jakarta.validation.constraints.NotNull(message = "请选择跟进时间") LocalDateTime nextFollowTime) {}

    public record CustomerRequest(@NotBlank(message = "请输入客户姓名")
                                  @jakarta.validation.constraints.Pattern(regexp = "[\\u4e00-\\u9fffA-Za-z0-9]+", message = "客户姓名只能包含中文、字母或数字") String name,
                                  @NotBlank(message = "请输入客户所在城市") String city, String address, String phone,
                                  @NotBlank(message = "请输入客户需求") String need, BigDecimal budget,
                                  String source, List<String> tags, String stage, Integer intentionScore, String intentionMode, LocalDateTime nextFollowTime) {
        CustomerService.CustomerCommand toCommand() {
            return new CustomerService.CustomerCommand(name, city, address, phone, need, budget, source, tags, stage, intentionScore, intentionMode, nextFollowTime);
        }
    }
}
