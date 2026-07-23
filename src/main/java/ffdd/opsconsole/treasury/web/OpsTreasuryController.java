package ffdd.opsconsole.treasury.web;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.treasury.application.OpsTreasuryService;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerBillView;
import ffdd.opsconsole.treasury.dto.TreasuryAlertAckRequest;
import ffdd.opsconsole.treasury.dto.TreasuryForecastConfigRequest;
import ffdd.opsconsole.treasury.dto.TreasuryInjectionRequest;
import ffdd.opsconsole.treasury.dto.TreasuryLedgerQueryRequest;
import ffdd.opsconsole.treasury.dto.TreasuryScopeRequest;
import ffdd.opsconsole.treasury.dto.TreasuryThresholdRequest;
import ffdd.opsconsole.treasury.dto.BankRunThresholdRequest;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

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

    @GetMapping("/coverage")
    @PreAuthorize("hasAnyAuthority('overview_b1_read', 'finance_d3_read', 'bi_l3_read')")
    public ApiResult<Map<String, Object>> coverage() {
        return treasuryService.coverage();
    }

    @GetMapping("/reserve")
    @PreAuthorize("hasAnyAuthority('finance_d3_read', 'overview_b2_read')")
    public ApiResult<Map<String, Object>> reserve() {
        return treasuryService.reserve();
    }

    @GetMapping("/liabilities")
    @PreAuthorize("hasAnyAuthority('finance_d3_read', 'overview_b2_read')")
    public ApiResult<Map<String, Object>> liabilities(@RequestParam(defaultValue = "true") boolean breakdown) {
        return treasuryService.liabilities(breakdown);
    }

    @GetMapping("/maturity-forecast")
    @PreAuthorize("hasAnyAuthority('finance_d3_read', 'overview_b2_read')")
    public ApiResult<Map<String, Object>> maturityForecast(@RequestParam(defaultValue = "7d") String window) {
        return treasuryService.maturityForecast(window);
    }

    @GetMapping("/net-exposure")
    @PreAuthorize("hasAnyAuthority('finance_d3_read', 'overview_b1_read')")
    public ApiResult<Map<String, Object>> netExposure(@RequestParam(defaultValue = "30d") String window) {
        return treasuryService.netExposure(window);
    }

    @GetMapping("/forecast-config")
    @PreAuthorize("hasAnyAuthority('finance_d3_read', 'overview_b2_read')")
    public ApiResult<Map<String, Object>> forecastConfig() {
        return treasuryService.forecastConfig();
    }

    @PutMapping("/forecast-config")
    @PreAuthorize("hasAnyAuthority('finance_d3_write', 'overview_b2_write')")
    public ApiResult<Map<String, Object>> updateForecastConfig(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) TreasuryForecastConfigRequest request) {
        return treasuryService.updateForecastConfig(idempotencyKey, request);
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

    @PatchMapping("/b-domain/bankrun-thresholds")
    @PreAuthorize("hasAnyAuthority('overview_b1_write','overview_b1_runrisk_write')")
    public ApiResult<Map<String, Object>> updateBankRunThresholds(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) BankRunThresholdRequest request) {
        return treasuryService.updateBankRunThresholds(idempotencyKey, request);
    }

    @PostMapping({"/reserve-injection", "/injections"})
    @PreAuthorize("hasAuthority('finance_d3_injection_create')")
    public ApiResult<Map<String, Object>> createInjection(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) TreasuryInjectionRequest request) {
        return treasuryService.createInjection(idempotencyKey, request);
    }

    @PatchMapping("/dual-ledger/scope")
    @PreAuthorize("hasAuthority('overview_b1_write')")
    public ApiResult<Map<String, Object>> updateScope(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) TreasuryScopeRequest request) {
        return treasuryService.updateScope(idempotencyKey, request);
    }

    @PatchMapping("/dual-ledger/thresholds")
    @PreAuthorize("hasAnyAuthority('overview_b1_redline_write','overview_b1_runrisk_write')")
    public ApiResult<Map<String, Object>> updateThresholds(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) TreasuryThresholdRequest request) {
        return treasuryService.updateThresholds(idempotencyKey, request);
    }

    @GetMapping(value = "/reconciliation/export", produces = "text/csv")
    @PreAuthorize("hasAuthority('finance_d3_export')")
    public ResponseEntity<byte[]> reconciliationExport() {
        return csv("d3-reconciliation.csv", treasuryService.reconciliationCsv());
    }

    @GetMapping(value = "/liabilities/export", produces = "text/csv")
    @PreAuthorize("hasAnyAuthority('finance_d3_export', 'overview_b2_export')")
    public ResponseEntity<byte[]> liabilitiesExport() {
        return csv("d3-liabilities.csv", treasuryService.liabilitiesCsv());
    }

    private ResponseEntity<byte[]> csv(String fileName, byte[] body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(body);
    }

    @GetMapping("/ledger/bills")
    @PreAuthorize("hasAuthority('finance_d4_read')")
    public ApiResult<PageResult<TreasuryLedgerBillView>> ledgerBills(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String bizNo,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize) {
        return treasuryService.ledgerBills(new TreasuryLedgerQueryRequest(
                type, userId, keyword, bizNo, status, from, to, pageNum, pageSize));
    }

    @GetMapping("/ledger/users/{userId}")
    @PreAuthorize("hasAuthority('finance_d4_read')")
    public ApiResult<Map<String, Object>> userLedger(@org.springframework.web.bind.annotation.PathVariable Long userId) {
        return treasuryService.userLedger(userId);
    }

    @GetMapping("/ledger/running-balance")
    @PreAuthorize("hasAnyAuthority('finance_d4_read','finance_d4_user_read')")
    public ApiResult<Map<String, Object>> runningBalance(@RequestParam Long userId) {
        return treasuryService.runningBalance(userId);
    }

    @GetMapping(value = "/ledger/export", produces = "text/csv")
    @PreAuthorize("hasAuthority('finance_d4_export')")
    public ResponseEntity<byte[]> ledgerExport(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String bizNo,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam String reason) {
        byte[] body = treasuryService.ledgerBillsCsv(new TreasuryLedgerQueryRequest(
                type, userId, keyword, bizNo, status, from, to, 1, 100), reason);
        return csv("d4-bills-masked.csv", body);
    }

}
