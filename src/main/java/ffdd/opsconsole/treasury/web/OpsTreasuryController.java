package ffdd.opsconsole.treasury.web;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.treasury.application.OpsTreasuryService;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerBillView;
import ffdd.opsconsole.treasury.dto.TreasuryAlertAckRequest;
import ffdd.opsconsole.treasury.dto.TreasuryInjectionRequest;
import ffdd.opsconsole.treasury.dto.TreasuryLedgerAdjustmentRequest;
import ffdd.opsconsole.treasury.dto.TreasuryLedgerQueryRequest;
import ffdd.opsconsole.treasury.dto.TreasuryScopeRequest;
import ffdd.opsconsole.treasury.dto.TreasuryThresholdRequest;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/treasury")
@RequiredArgsConstructor
public class OpsTreasuryController {
    private final OpsTreasuryService treasuryService;

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('finance_d3_read')")
    public ApiResult<Map<String, Object>> overview(@RequestParam(defaultValue = "7") int days) {
        return treasuryService.overview(days);
    }

    @GetMapping("/dual-ledger")
    @PreAuthorize("hasAuthority('finance_d3_read')")
    public ApiResult<Map<String, Object>> dualLedger() {
        return treasuryService.dualLedger();
    }

    @GetMapping("/b-domain")
    @PreAuthorize("hasAuthority('overview_b1_read')")
    public ApiResult<Map<String, Object>> bDomainDashboard() {
        return treasuryService.bDomainDashboard();
    }

    @PostMapping("/b-domain/alerts/{alertId}/ack")
    @PreAuthorize("hasAuthority('overview_b1_write')")
    public ApiResult<Map<String, Object>> acknowledgeBDomainAlert(
            @PathVariable String alertId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) TreasuryAlertAckRequest request) {
        return treasuryService.acknowledgeBDomainAlert(alertId, idempotencyKey, request);
    }

    @PostMapping("/injections")
    @PreAuthorize("hasAuthority('finance_d3_injection_create')")
    public ApiResult<Map<String, Object>> createInjection(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) TreasuryInjectionRequest request) {
        return treasuryService.createInjection(idempotencyKey, request);
    }

    @PatchMapping("/dual-ledger/scope")
    @PreAuthorize("hasAuthority('finance_d3_write')")
    public ApiResult<Map<String, Object>> updateScope(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) TreasuryScopeRequest request) {
        return treasuryService.updateScope(idempotencyKey, request);
    }

    @PatchMapping("/dual-ledger/thresholds")
    @PreAuthorize("hasAnyAuthority('finance_d3_redline_pct_write','finance_d3_healthy_pct_write','finance_d3_runrisk_pct_write')")
    public ApiResult<Map<String, Object>> updateThresholds(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) TreasuryThresholdRequest request) {
        return treasuryService.updateThresholds(idempotencyKey, request);
    }

    @GetMapping("/ledger/bills")
    @PreAuthorize("hasAuthority('finance_d4_read')")
    public ApiResult<PageResult<TreasuryLedgerBillView>> ledgerBills(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize) {
        return treasuryService.ledgerBills(new TreasuryLedgerQueryRequest(type, userId, keyword, pageNum, pageSize));
    }

    @GetMapping("/ledger/users/{userId}")
    @PreAuthorize("hasAuthority('finance_d4_read')")
    public ApiResult<Map<String, Object>> userLedger(@org.springframework.web.bind.annotation.PathVariable Long userId) {
        return treasuryService.userLedger(userId);
    }

    @PostMapping("/ledger/adjustments")
    @PreAuthorize("hasAuthority('finance_d4_adjustment_create')")
    public ApiResult<Map<String, Object>> createLedgerAdjustment(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) TreasuryLedgerAdjustmentRequest request) {
        return treasuryService.createLedgerAdjustment(idempotencyKey, request);
    }
}
