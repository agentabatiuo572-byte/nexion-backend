package ffdd.opsconsole.finance.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.finance.application.D1BankOrderService;
import ffdd.opsconsole.finance.dto.HdPayManualCreditRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/finance/vietqr/orders")
@RequiredArgsConstructor
public class OpsD1BankOrderController {
    private final D1BankOrderService service;
    private final AuditLogService audit;

    @GetMapping
    @PreAuthorize("hasAuthority('finance_d1_read')")
    public ApiResult<Map<String, Object>> list(@RequestParam(required = false) String paymentRail,
            @RequestParam(required = false) String status, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer pageNum, @RequestParam(required = false) Integer pageSize) {
        return service.list(paymentRail, status, keyword, pageNum, pageSize);
    }

    @PostMapping("/{intentNo}/manual-credit")
    @PreAuthorize("hasAuthority('finance_d1_bank_reconcile')")
    public ApiResult<Map<String, Object>> manualCredit(@PathVariable String intentNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String key,
            @RequestBody(required = false) HdPayManualCreditRequest request) {
        ApiResult<Map<String, Object>> result;
        try { result = service.manualCredit(intentNo, key, request); }
        catch (BizException ex) { result = ApiResult.fail(ex.getCode(), ex.getMessage()); }
        if (result.getCode() != 0) {
            audit.recordRequiredInNewTransaction(AuditLogWriteRequest.builder()
                    .action("D1_HDPAY_MANUAL_CREDIT_REJECTED").resourceType("HDPAY_PAYIN_ORDER")
                    .resourceId(intentNo).actorType("ADMIN")
                    .actorUsername(AdminActorResolver.resolve("authenticated-finance-admin"))
                    .result("REJECTED").riskLevel("CRITICAL")
                    .detail(Map.of("code", result.getCode(), "message", result.getMessage())).build());
        }
        return result;
    }
}
