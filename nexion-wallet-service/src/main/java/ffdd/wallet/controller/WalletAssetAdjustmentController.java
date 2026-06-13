package ffdd.wallet.controller;

import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import ffdd.common.audit.AuditLogService;
import ffdd.common.audit.AuditLogWriteRequest;
import ffdd.wallet.domain.WalletAssetAdjustment;
import ffdd.wallet.dto.AssetAdjustmentQueryRequest;
import ffdd.wallet.dto.CreateAssetAdjustmentRequest;
import ffdd.wallet.dto.ReviewAssetAdjustmentRequest;
import ffdd.wallet.service.WalletAssetAdjustmentService;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/wallet/ops/asset-adjustments")
@RequiredArgsConstructor
public class WalletAssetAdjustmentController {
    private final WalletAssetAdjustmentService adjustmentService;
    private final AuditLogService auditLogService;

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_WALLET_READ')")
    public ApiResult<PageResult<WalletAssetAdjustment>> page(AssetAdjustmentQueryRequest request) {
        return ApiResult.ok(adjustmentService.page(request));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_WALLET_WRITE')")
    public ApiResult<WalletAssetAdjustment> create(@Valid @RequestBody CreateAssetAdjustmentRequest request) {
        WalletAssetAdjustment row = adjustmentService.create(request);
        audit("WALLET_ASSET_ADJUSTMENT_CREATED", row, request.getMaker());
        return ApiResult.ok(row);
    }

    @PostMapping("/{adjustmentNo}/review")
    @PreAuthorize("hasAuthority('PERM_WALLET_WRITE')")
    public ApiResult<WalletAssetAdjustment> review(
            @PathVariable String adjustmentNo,
            @Valid @RequestBody ReviewAssetAdjustmentRequest request) {
        WalletAssetAdjustment row = adjustmentService.review(adjustmentNo, request);
        audit("WALLET_ASSET_ADJUSTMENT_" + row.getStatus(), row, request.getChecker());
        return ApiResult.ok(row);
    }

    private void audit(String action, WalletAssetAdjustment row, String operator) {
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType("WALLET_ASSET_ADJUSTMENT")
                .resourceId(row.getAdjustmentNo())
                .bizNo(row.getAdjustmentNo())
                .userId(row.getUserId())
                .riskLevel("HIGH")
                .detail(Map.of(
                        "asset", row.getAsset(),
                        "direction", row.getDirection(),
                        "amount", row.getAmount(),
                        "status", row.getStatus(),
                        "operator", operator,
                        "ledgerId", row.getLedgerId() == null ? "" : row.getLedgerId()))
                .build());
    }
}
