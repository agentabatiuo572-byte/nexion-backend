package ffdd.earnings.controller;

import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import ffdd.earnings.domain.EarningSummary;
import ffdd.earnings.dto.EarningSummaryQueryRequest;
import ffdd.earnings.service.EarningsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/earnings/summaries")
public class EarningSummaryController {
    private final EarningsService earningsService;

    public EarningSummaryController(EarningsService earningsService) {
        this.earningsService = earningsService;
    }

    @GetMapping
    public ApiResult<PageResult<EarningSummary>> page(EarningSummaryQueryRequest request) {
        return ApiResult.ok(earningsService.pageSummaries(request));
    }
}
