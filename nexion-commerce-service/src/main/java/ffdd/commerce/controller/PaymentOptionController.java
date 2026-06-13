package ffdd.commerce.controller;

import ffdd.commerce.dto.PaymentOptionResponse;
import ffdd.commerce.service.PaymentOptionService;
import ffdd.common.api.ApiResult;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentOptionController {
    private final PaymentOptionService paymentOptionService;

    public PaymentOptionController(PaymentOptionService paymentOptionService) {
        this.paymentOptionService = paymentOptionService;
    }

    @GetMapping("/commerce/app/payment-options")
    public ApiResult<List<PaymentOptionResponse>> appPaymentOptions() {
        return ApiResult.ok(paymentOptionService.listOptions());
    }
}
