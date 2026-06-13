package ffdd.commerce.controller;

import ffdd.commerce.domain.CommerceOrder;
import ffdd.commerce.domain.PaymentRecord;
import ffdd.commerce.dto.PaymentAnomalyResponse;
import ffdd.commerce.dto.PaymentCallbackResponse;
import ffdd.commerce.dto.PaymentCheckoutRequest;
import ffdd.commerce.dto.PaymentCheckoutResponse;
import ffdd.commerce.dto.PaymentOpsResult;
import ffdd.commerce.dto.PaymentRecordQueryRequest;
import ffdd.commerce.dto.PaymentReconcileResponse;
import ffdd.commerce.service.CommerceService;
import ffdd.commerce.service.PaymentService;
import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import ffdd.common.audit.AuditLogService;
import ffdd.common.audit.AuditLogWriteRequest;
import ffdd.common.exception.BizException;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
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
    private final CommerceService commerceService;
    private final PaymentService paymentService;
    private final AuditLogService auditLogService;

    public PaymentController(
            CommerceService commerceService,
            PaymentService paymentService,
            AuditLogService auditLogService) {
        this.commerceService = commerceService;
        this.paymentService = paymentService;
        this.auditLogService = auditLogService;
    }

    @PostMapping("/checkout")
    @PreAuthorize("hasAuthority('PERM_COMMERCE_WRITE') or hasAuthority('ROLE_USER')")
    public ApiResult<PaymentCheckoutResponse> checkout(@Valid @RequestBody PaymentCheckoutRequest request) {
        CommerceOrder order = commerceService.getOrder(request.getOrderNo());
        assertVisibleToCurrentUser(order.getUserId(), "Order does not belong to authenticated user");
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
    @PreAuthorize("hasAuthority('PERM_COMMERCE_READ') or hasAuthority('ROLE_USER')")
    public ApiResult<PageResult<PaymentRecord>> page(PaymentRecordQueryRequest request) {
        Long roleUserId = currentRoleUserId();
        if (roleUserId != null) {
            request.setUserId(roleUserId);
        }
        return ApiResult.ok(paymentService.pageRecords(request));
    }

    @GetMapping("/{paymentNo}")
    @PreAuthorize("hasAuthority('PERM_COMMERCE_READ') or hasAuthority('ROLE_USER')")
    public ApiResult<PaymentRecord> detail(@PathVariable String paymentNo) {
        PaymentRecord record = paymentService.getRecord(paymentNo);
        assertVisibleToCurrentUser(record.getUserId(), "Payment does not belong to authenticated user");
        return ApiResult.ok(record);
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

    private void assertVisibleToCurrentUser(Long ownerUserId, String message) {
        Long roleUserId = currentRoleUserId();
        if (roleUserId != null && !roleUserId.equals(ownerUserId)) {
            throw new BizException(message);
        }
    }

    private Long currentRoleUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getAuthorities().stream().noneMatch(a -> "ROLE_USER".equals(a.getAuthority()))) {
            return null;
        }
        String subject = String.valueOf(authentication.getPrincipal());
        if (!StringUtils.hasText(subject)) {
            return null;
        }
        try {
            return Long.valueOf(subject);
        } catch (NumberFormatException ignored) {
            throw new BizException("Authenticated user id is invalid");
        }
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
