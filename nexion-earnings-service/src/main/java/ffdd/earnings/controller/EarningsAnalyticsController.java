package ffdd.earnings.controller;

import ffdd.common.api.ApiResult;
import ffdd.earnings.dto.EarningMilestonesResponse;
import ffdd.earnings.dto.EarningTrendQueryRequest;
import ffdd.earnings.dto.EarningTrendResponse;
import ffdd.earnings.dto.MissedIncomeQueryRequest;
import ffdd.earnings.dto.MissedIncomeResponse;
import ffdd.earnings.service.EarningsAnalyticsService;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/earnings/analytics")
public class EarningsAnalyticsController {
    private final EarningsAnalyticsService earningsAnalyticsService;

    public EarningsAnalyticsController(EarningsAnalyticsService earningsAnalyticsService) {
        this.earningsAnalyticsService = earningsAnalyticsService;
    }

    @GetMapping("/trend")
    public ApiResult<EarningTrendResponse> trend(@Valid EarningTrendQueryRequest request) {
        return ApiResult.ok(earningsAnalyticsService.trend(request));
    }

    @GetMapping("/milestones")
    public ApiResult<EarningMilestonesResponse> milestones(@RequestParam Long userId) {
        return ApiResult.ok(earningsAnalyticsService.milestones(userId));
    }

    @GetMapping("/missed-income")
    public ApiResult<MissedIncomeResponse> missedIncome(@Valid MissedIncomeQueryRequest request) {
        return ApiResult.ok(earningsAnalyticsService.missedIncome(request));
    }
}
