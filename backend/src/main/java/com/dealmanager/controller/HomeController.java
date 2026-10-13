package com.dealmanager.controller;

import com.dealmanager.service.DailyAdviceService;
import com.dealmanager.service.HomeService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/home")
@RequiredArgsConstructor
public class HomeController {
    private final HomeService homeService;
    private final DailyAdviceService dailyAdviceService;

    @GetMapping("/overview")
    public HomeService.Overview overview(@AuthenticationPrincipal Long userId) {
        return homeService.overview(userId);
    }

    /** 每个北京日期首次打开首页自动生成并保存；“重新生成”显式传 refresh=true。 */
    @PostMapping("/daily-advice")
    public DailyAdviceService.Daily dailyAdvice(@AuthenticationPrincipal Long userId,
                                                @RequestParam(defaultValue = "false") boolean refresh) {
        return dailyAdviceService.daily(userId, refresh);
    }
}
