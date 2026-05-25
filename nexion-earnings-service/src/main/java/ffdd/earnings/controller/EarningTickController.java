package ffdd.earnings.controller;

import ffdd.common.api.ApiResult;
import ffdd.earnings.dto.EarningTickBatchRequest;
import ffdd.earnings.dto.EarningTickBatchResult;
import ffdd.earnings.service.EarningTickSettlementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/earnings/ticks")
public class EarningTickController {
    private final EarningTickSettlementService tickSettlementService;

    public EarningTickController(EarningTickSettlementService tickSettlementService) {
        this.tickSettlementService = tickSettlementService;
    }

    @PostMapping("/settle-batch")
    @PreAuthorize("hasAuthority('PERM_EARNINGS_WRITE')")
    public ApiResult<EarningTickBatchResult> settleBatch(@Valid @RequestBody EarningTickBatchRequest request) {
        return ApiResult.ok(tickSettlementService.settleBatch(request));
    }

    @PostMapping("/settle-device-snapshot")
    @PreAuthorize("hasAuthority('PERM_EARNINGS_WRITE')")
    public ApiResult<EarningTickBatchResult> settleDeviceSnapshot(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    LocalDateTime tickAt,
            @RequestParam(required = false) @Min(1) @Max(500) Integer limit) {
        return ApiResult.ok(tickSettlementService.settleDeviceTicks(tickAt, limit));
    }
}
