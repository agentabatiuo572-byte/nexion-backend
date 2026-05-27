package ffdd.commerce.genesis;

import ffdd.commerce.genesis.domain.GenesisHolding;
import ffdd.commerce.genesis.domain.GenesisOrder;
import ffdd.commerce.genesis.domain.GenesisSeries;
import ffdd.commerce.genesis.dto.GenesisHoldingQueryRequest;
import ffdd.commerce.genesis.dto.GenesisOrderQueryRequest;
import ffdd.commerce.genesis.dto.GenesisPurchaseRequest;
import ffdd.commerce.genesis.dto.GenesisSeriesCreateRequest;
import ffdd.commerce.genesis.dto.GenesisSeriesUpdateRequest;
import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import ffdd.common.audit.AuditLogService;
import ffdd.common.audit.AuditLogWriteRequest;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/genesis")
public class GenesisController {
    private final GenesisService genesisService;
    private final AuditLogService auditLogService;

    public GenesisController(GenesisService genesisService, AuditLogService auditLogService) {
        this.genesisService = genesisService;
        this.auditLogService = auditLogService;
    }

    @GetMapping("/overview")
    public ApiResult<Map<String, Object>> overview(@RequestParam(required = false) Long userId) {
        return ApiResult.ok(genesisService.overview(userId));
    }

    @PostMapping("/orders")
    public ApiResult<GenesisOrder> purchase(@Valid @RequestBody GenesisPurchaseRequest request) {
        GenesisOrder order = genesisService.purchase(request);
        auditOrder("GENESIS_PURCHASE", order);
        return ApiResult.ok(order);
    }

    @GetMapping("/orders")
    @PreAuthorize("hasAuthority('PERM_COMMERCE_READ') or hasAuthority('ROLE_USER')")
    public ApiResult<PageResult<GenesisOrder>> orders(GenesisOrderQueryRequest request) {
        return ApiResult.ok(genesisService.pageOrders(request));
    }

    @GetMapping("/orders/{orderNo}")
    @PreAuthorize("hasAuthority('PERM_COMMERCE_READ') or hasAuthority('ROLE_USER')")
    public ApiResult<GenesisOrder> order(@PathVariable String orderNo) {
        return ApiResult.ok(genesisService.getOrder(orderNo));
    }

    @GetMapping("/holdings")
    @PreAuthorize("hasAuthority('PERM_COMMERCE_READ') or hasAuthority('ROLE_USER')")
    public ApiResult<PageResult<GenesisHolding>> holdings(GenesisHoldingQueryRequest request) {
        return ApiResult.ok(genesisService.pageHoldings(request));
    }

    @GetMapping("/series")
    public ApiResult<PageResult<GenesisSeries>> series(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") Long pageNum,
            @RequestParam(defaultValue = "20") Long pageSize) {
        return ApiResult.ok(genesisService.pageSeries(pageNum, pageSize, status));
    }

    @PostMapping("/series")
    @PreAuthorize("hasAuthority('PERM_COMMERCE_WRITE')")
    public ApiResult<GenesisSeries> createSeries(@Valid @RequestBody GenesisSeriesCreateRequest request) {
        return ApiResult.ok(genesisService.createSeries(request));
    }

    @PatchMapping("/series/{id}")
    @PreAuthorize("hasAuthority('PERM_COMMERCE_WRITE')")
    public ApiResult<GenesisSeries> updateSeries(
            @PathVariable Long id,
            @Valid @RequestBody GenesisSeriesUpdateRequest request) {
        return ApiResult.ok(genesisService.updateSeries(id, request));
    }

    private void auditOrder(String action, GenesisOrder order) {
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType("GENESIS_ORDER")
                .resourceId(order.getOrderNo())
                .bizNo(order.getOrderNo())
                .userId(order.getUserId())
                .riskLevel("HIGH")
                .detail(detail(
                        "seriesCode", order.getSeriesCode(),
                        "quantity", order.getQuantity(),
                        "amountUsdt", order.getAmountUsdt(),
                        "status", order.getStatus(),
                        "riskDecisionId", order.getRiskDecisionId(),
                        "walletLedgerId", order.getWalletLedgerId()))
                .build());
    }

    private Map<String, Object> detail(Object... pairs) {
        Map<String, Object> detail = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            if (value != null) {
                detail.put(String.valueOf(pairs[i]), value);
            }
        }
        return detail;
    }
}
