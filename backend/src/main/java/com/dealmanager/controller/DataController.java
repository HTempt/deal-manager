package com.dealmanager.controller;

import com.dealmanager.domain.Deal;
import com.dealmanager.service.DataService;
import com.dealmanager.service.BusinessSummaryService;
import com.dealmanager.service.AiQuota;
import com.dealmanager.service.FollowEfficiencyService;
import com.dealmanager.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;

@RestController
@RequestMapping("/api/data")
@RequiredArgsConstructor
public class DataController {
    private final DataService dataService;
    private final BusinessSummaryService businessSummaryService;
    private final FollowEfficiencyService followEfficiencyService;
    @GetMapping("/follow-efficiency")
    public FollowEfficiencyService.Efficiency followEfficiency(@AuthenticationPrincipal Long userId,
                                                               @RequestParam(required = false) String month) {
        return followEfficiencyService.efficiency(userId, parse(month));
    }
    @PostMapping("/summary")
    public BusinessSummaryService.Summary summary(@AuthenticationPrincipal Long userId,
                                                   @RequestParam(required = false) String month,
                                                   @RequestParam(defaultValue = "false") boolean refresh) {
        return businessSummaryService.summarize(userId, parse(month), refresh);
    }
    @GetMapping("/overview")
    public DataService.Overview overview(@AuthenticationPrincipal Long userId,
                                         @RequestParam(required = false) String month) {
        return dataService.overview(userId, parse(month));
    }
    @GetMapping("/funnel")
    public DataService.Funnel funnel(@AuthenticationPrincipal Long userId,
                                     @RequestParam(required = false) String month) {
        return dataService.funnel(userId, parse(month));
    }
    @GetMapping("/source")
    public List<DataService.SourceStat> source(@AuthenticationPrincipal Long userId,
                                               @RequestParam(required = false) String month) {
        return dataService.sources(userId, parse(month));
    }
    @GetMapping("/deals")
    public List<Deal> deals(@AuthenticationPrincipal Long userId, @RequestParam(required = false) String month) {
        return dataService.dealList(userId, parse(month));
    }
    private static YearMonth parse(String month) {
        try {
            return month == null || month.isBlank() ? YearMonth.now(AiQuota.ZONE) : YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("月份格式应为YYYY-MM");
        }
    }
}
