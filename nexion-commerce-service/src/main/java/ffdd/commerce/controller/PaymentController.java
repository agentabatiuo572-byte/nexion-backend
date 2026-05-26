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
import jakarta.validation.Valid;
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

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/checkout")
    public ApiResult<PaymentCheckoutResponse> checkout(@Valid @RequestBody PaymentCheckoutRequest request) {
        return ApiResult.ok(paymentService.checkout(request));
    }

    @PostMapping("/callbacks/{provider}")
    public ApiResult<PaymentCallbackResponse> callback(
            @PathVariable String provider,
            @RequestHeader Map<String, String> headers,
            @RequestBody String rawBody) {
        return ApiResult.ok(paymentService.handleCallback(provider, headers, rawBody));
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
        return ApiResult.ok(paymentService.expirePendingPayments(limit == null ? 20 : limit));
    }

    @PostMapping("/ops/reconcile/{paymentNo}")
    @PreAuthorize("hasAuthority('PERM_COMMERCE_WRITE')")
    public ApiResult<PaymentReconcileResponse> reconcile(@PathVariable String paymentNo) {
        return ApiResult.ok(paymentService.reconcilePayment(paymentNo));
    }

    @PostMapping("/ops/reconcile-due")
    @PreAuthorize("hasAuthority('PERM_COMMERCE_WRITE')")
    public ApiResult<PaymentOpsResult> reconcileDue(@RequestParam(required = false) Integer limit) {
        return ApiResult.ok(paymentService.reconcileDuePayments(limit == null ? 20 : limit));
    }

    @GetMapping("/ops/anomalies")
    @PreAuthorize("hasAuthority('PERM_COMMERCE_READ')")
    public ApiResult<List<PaymentAnomalyResponse>> anomalies(@RequestParam(required = false) Integer limit) {
        return ApiResult.ok(paymentService.listPaymentAnomalies(limit == null ? 20 : limit));
    }
}
