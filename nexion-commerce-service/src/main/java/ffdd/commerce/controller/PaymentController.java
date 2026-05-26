package ffdd.commerce.controller;

import ffdd.commerce.domain.PaymentRecord;
import ffdd.commerce.dto.PaymentCallbackResponse;
import ffdd.commerce.dto.PaymentCheckoutRequest;
import ffdd.commerce.dto.PaymentCheckoutResponse;
import ffdd.commerce.dto.PaymentRecordQueryRequest;
import ffdd.commerce.service.PaymentService;
import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
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
}
