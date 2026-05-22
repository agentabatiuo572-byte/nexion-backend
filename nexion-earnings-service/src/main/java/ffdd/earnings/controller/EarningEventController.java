package ffdd.earnings.controller;

import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import ffdd.earnings.domain.EarningEvent;
import ffdd.earnings.dto.EarningEventQueryRequest;
import ffdd.earnings.dto.ReceiptSettleRequest;
import ffdd.earnings.dto.ReceiptSettleResponse;
import ffdd.earnings.service.EarningsService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/earnings/events")
public class EarningEventController {
    private final EarningsService earningsService;

    public EarningEventController(EarningsService earningsService) {
        this.earningsService = earningsService;
    }

    @GetMapping
    public ApiResult<PageResult<EarningEvent>> page(EarningEventQueryRequest request) {
        return ApiResult.ok(earningsService.pageEvents(request));
    }

    @PostMapping("/settle-receipt")
    public ApiResult<ReceiptSettleResponse> settleReceipt(@Valid @RequestBody ReceiptSettleRequest request) {
        return ApiResult.ok(earningsService.settleReceipt(request));
    }
}
