package ffdd.opsconsole.finance.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.finance.application.OpsVietnamPaymentService;
import ffdd.opsconsole.finance.dto.FxQuoteUpdateRequest;
import ffdd.opsconsole.finance.dto.VietQrBankAccountCommandRequest;
import ffdd.opsconsole.finance.dto.VietQrBankAccountCreateRequest;
import ffdd.opsconsole.finance.dto.VietQrConfigUpdateRequest;
import ffdd.opsconsole.finance.dto.VietQrReconciliationCommandRequest;
import ffdd.opsconsole.finance.dto.VietQrReceiptRegistrationRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
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
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/finance")
@RequiredArgsConstructor
public class OpsVietnamPaymentController {
    private final OpsVietnamPaymentService service;
    private final AuditLogService auditLogService;

    @GetMapping("/vietqr/overview")
    @PreAuthorize("hasAuthority('finance_d1_read')")
    public ApiResult<Map<String, Object>> vietQrOverview(
            @RequestParam(required = false) String view,
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize) {
        return service.vietQrOverview(view, pageNum, pageSize);
    }

    @PostMapping("/vietqr/reconciliations/{id}/actions/{action}")
    @PreAuthorize("hasAuthority('finance_d1_bank_reconcile')")
    public ApiResult<Map<String, Object>> reconcile(
            @PathVariable Long id,
            @PathVariable String action,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) VietQrReconciliationCommandRequest request) {
        return auditedWrite(
                "D1_VIETQR_RECONCILIATION_REJECTED",
                "VIETQR_RECONCILIATION",
                String.valueOf(id),
                () -> service.reconcile(id, action, idempotencyKey, request));
    }

    @PostMapping("/vietqr/receipts")
    @PreAuthorize("hasAuthority('finance_d1_bank_reconcile')")
    public ApiResult<Map<String, Object>> registerVietQrReceipt(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) VietQrReceiptRegistrationRequest request) {
        return auditedWrite(
                "D1_VIETQR_RECEIPT_REGISTER_REJECTED",
                "VIETQR_RECONCILIATION",
                request == null ? "NEW" : String.valueOf(request.paymentReference()),
                () -> service.registerVietQrReceipt(idempotencyKey, request));
    }

    @PostMapping("/vietqr/accounts")
    @PreAuthorize("hasAuthority('finance_d1_bank_account_manage')")
    public ApiResult<Map<String, Object>> createBankAccount(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) VietQrBankAccountCreateRequest request) {
        return auditedWrite(
                "D1_VIETQR_ACCOUNT_CREATE_REJECTED",
                "VIETQR_BANK_ACCOUNT",
                "NEW",
                () -> service.createBankAccount(idempotencyKey, request));
    }

    @PatchMapping("/vietqr/accounts/{id}")
    @PreAuthorize("hasAuthority('finance_d1_bank_account_manage')")
    public ApiResult<Map<String, Object>> updateBankAccount(
            @PathVariable Long id,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) VietQrBankAccountCommandRequest request) {
        return auditedWrite(
                "D1_VIETQR_ACCOUNT_UPDATE_REJECTED",
                "VIETQR_BANK_ACCOUNT",
                String.valueOf(id),
                () -> service.updateBankAccount(id, idempotencyKey, request));
    }

    @PatchMapping("/vietqr/config")
    @PreAuthorize("hasAuthority('finance_d1_bank_config_manage')")
    public ApiResult<Map<String, Object>> updateVietQrConfig(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) VietQrConfigUpdateRequest request) {
        return auditedWrite(
                "D1_VIETQR_CONFIG_REJECTED",
                "VIETQR_CONFIG",
                "GLOBAL",
                () -> service.updateVietQrConfig(idempotencyKey, request));
    }

    @GetMapping("/fx-quote")
    @PreAuthorize("hasAuthority('finance_d6_read')")
    public ApiResult<Map<String, Object>> fxQuote() {
        return service.fxQuote();
    }

    @PatchMapping("/fx-quote")
    @PreAuthorize("hasAuthority('finance_d6_manage')")
    public ApiResult<Map<String, Object>> updateFxQuote(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) FxQuoteUpdateRequest request) {
        return auditedWrite(
                "D6_FX_QUOTE_REJECTED",
                "FX_QUOTE_CONFIG",
                "GLOBAL",
                () -> service.updateFxQuote(idempotencyKey, request));
    }

    private <T> ApiResult<T> auditedWrite(String action, String resourceType, String resourceId,
                                          Supplier<ApiResult<T>> operation) {
        ApiResult<T> result;
        try {
            result = operation.get();
        } catch (BizException rejected) {
            result = ApiResult.fail(rejected.getCode(), rejected.getMessage());
        }
        if (result.getCode() != 0) {
            auditLogService.recordRequiredInNewTransaction(AuditLogWriteRequest.builder()
                    .action(action)
                    .resourceType(resourceType)
                    .resourceId(resourceId)
                    .actorType("ADMIN")
                    .actorUsername(AdminActorResolver.resolve("system"))
                    .result("REJECTED")
                    .riskLevel("CRITICAL")
                    .detail(Map.of(
                            "code", result.getCode(),
                            "message", String.valueOf(result.getMessage())))
                    .build());
        }
        return result;
    }
}
