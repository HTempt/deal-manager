package com.dealmanager.controller;

import com.dealmanager.common.ApiException;
import com.dealmanager.service.AiQuota;
import com.dealmanager.service.BusinessSummaryService;
import com.dealmanager.service.DataService;
import com.dealmanager.service.FollowEfficiencyService;
import org.junit.jupiter.api.Test;
import java.time.YearMonth;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DataControllerTest {
    @Test
    void funnelUsesAuthenticatedUserAndSharedMonthParsing() {
        DataService data = mock(DataService.class);
        DataController controller = new DataController(data, mock(BusinessSummaryService.class), mock(FollowEfficiencyService.class));
        controller.funnel(7L, "2026-10");
        verify(data).funnel(7L, YearMonth.of(2026, 10));
        controller.funnel(8L, null);
        verify(data).funnel(8L, YearMonth.now(AiQuota.ZONE));
        assertThrows(ApiException.class, () -> controller.funnel(7L, "not-a-month"));
        verifyNoMoreInteractions(data);
    }

    @Test
    void followEfficiencyUsesAuthenticatedUserAndRejectsInvalidMonth() {
        FollowEfficiencyService efficiency = mock(FollowEfficiencyService.class);
        DataController controller = new DataController(mock(DataService.class), mock(BusinessSummaryService.class), efficiency);
        controller.followEfficiency(7L, "2026-10");
        verify(efficiency).efficiency(7L, YearMonth.of(2026, 10));
        controller.followEfficiency(8L, null);
        verify(efficiency).efficiency(8L, YearMonth.now(AiQuota.ZONE));
        assertThrows(ApiException.class, () -> controller.followEfficiency(7L, "bad-month"));
        verifyNoMoreInteractions(efficiency);
    }
}
