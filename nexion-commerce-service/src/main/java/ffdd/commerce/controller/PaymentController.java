package ffdd.commerce.controller;

import ffdd.commerce.domain.PaymentRecord;
import ffdd.commerce.dto.PaymentAnomalyResponse;
import ffdd.commerce.dto.PaymentCallbackResponse;
import ffdd.commerce.dto.PaymentCheckoutRequest;
import ffdd.commerce.dto.PaymentCheckoutResponse;
import ffdd.commerce.dto.PaymentOpsResult;
import ffdd.commerce.dto.PaymentRecordQueryRequest;
import ffdd.commerce.dto.PaymentReconcileResponse;
import ffdd.commerce.service.PaymentService;
import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import ffdd.common.audit.AuditLogService;
import ffdd.common.audit.AuditLogWriteRequest;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
@RequestMapping("/commerce/payments")
public class PaymentController {
    private final PaymentService paymentService;
    private final AuditLogService auditLogService;

    public PaymentController(PaymentService paymentService, AuditLogService auditLogService) {
        this.paymentService = paymentService;
        this.auditLogService = auditLogService;
    }

    @PostMapping("/checkout")
    public ApiResult<PaymentCheckoutResponse> checkout(@Valid @RequestBody PaymentCheckoutRequest request) {
        PaymentCheckoutResponse response = paymentService.checkout(request);
        auditPayment("PAYMENT_CHECKOUT", response.getPaymentNo(), response.getOrderNo(), null, detail(
                "provider", response.getProvider(),
                "paymentStatus", response.getPaymentStatus(),
                "amountUsdt", response.getAmountUsdt(),
                "currency", response.getCurrency()));
        return ApiResult.ok(response);
    }

    @PostMapping("/callbacks/{provider}")
    public ApiResult<PaymentCallbackResponse> callback(
            @PathVariable String provider,
            @RequestHeader Map<String, String> headers,
            @RequestBody String rawBody) {
        PaymentCallbackResponse response = paymentService.handleCallback(provider, headers, rawBody);
        auditPayment("PAYMENT_CALLBACK", response.getPaymentNo(), response.getOrderNo(), null, detail(
                "provider", response.getProvider(),
                "providerEventId", response.getProviderEventId(),
                "paymentStatus", response.getPaymentStatus(),
                "orderPaymentStatus", response.getOrderPaymentStatus(),
                "activationStatus", response.getActivationStatus(),
                "duplicate", response.isDuplicate()));
        return ApiResult.ok(response);
    }

    @GetMapping
    public ApiResult<PageResult<PaymentRecord>> page(PaymentRecordQueryRequest request) {
        return ApiResult.ok(paymentService.pageRecords(request));
    }

    @GetMapping("/{paymentNo}")
    public ApiResult<PaymentRecord> detail(@PathVariable String paymentNo) {
        return ApiResult.ok(paymentService.getRecord(paymentNo));
    }

    @PostMapping("/ops/expire-pending")
    @PreAuthorize("hasAuthority('PERM_COMMERCE_WRITE')")
    public ApiResult<PaymentOpsResult> expirePending(@RequestParam(required = false) Integer limit) {
        PaymentOpsResult result = paymentService.expirePendingPayments(limit == null ? 20 : limit);
        auditPayment("PAYMENT_EXPIRE_PENDING", null, null, null, detail(
                "scanned", result.getScanned(),
                "expired", result.getExpired(),
                "skipped", result.getSkipped(),
                "errors", result.getErrors(),
                "paymentNos", result.getPaymentNos()));
        return ApiResult.ok(result);
    }

    @PostMapping("/ops/reconcile/{paymentNo}")
    @PreAuthorize("hasAuthority('PERM_COMMERCE_WRITE')")
    public ApiResult<PaymentReconcileResponse> reconcile(@PathVariable String paymentNo) {
        PaymentReconcileResponse response = paymentService.reconcilePayment(paymentNo);
        auditPayment("PAYMENT_RECONCILE", response.getPaymentNo(), response.getOrderNo(), null, detail(
                "provider", response.getProvider(),
                "providerStatus", response.getProviderStatus(),
                "paymentStatus", response.getPaymentStatus(),
                "orderPaymentStatus", response.getOrderPaymentStatus(),
                "changed", response.isChanged()));
        return ApiResult.ok(response);
    }

    @PostMapping("/ops/reconcile-due")
    @PreAuthorize("hasAuthority('PERM_COMMERCE_WRITE')")
    public ApiResult<PaymentOpsResult> reconcileDue(@RequestParam(required = false) Integer limit) {
        PaymentOpsResult result = paymentService.reconcileDuePayments(limit == null ? 20 : limit);
        auditPayment("PAYMENT_RECONCILE_DUE", null, null, null, detail(
                "scanned", result.getScanned(),
                "reconciled", result.getReconciled(),
                "paid", result.getPaid(),
                "failed", result.getFailed(),
                "skipped", result.getSkipped(),
                "errors", result.getErrors(),
                "paymentNos", result.getPaymentNos()));
        return ApiResult.ok(result);
    }

    @GetMapping("/ops/anomalies")
    @PreAuthorize("hasAuthority('PERM_COMMERCE_READ')")
    public ApiResult<List<PaymentAnomalyResponse>> anomalies(@RequestParam(required = false) Integer limit) {
        return ApiResult.ok(paymentService.listPaymentAnomalies(limit == null ? 20 : limit));
    }

    private void auditPayment(String action, String paymentNo, String orderNo, Long userId, Map<String, Object> detail) {
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType("PAYMENT")
                .resourceId(paymentNo)
                .bizNo(paymentNo == null ? orderNo : paymentNo)
                .userId(userId)
                .riskLevel("HIGH")
                .detail(detail)
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
