package ffdd.opsconsole.finance.web;

import ffdd.opsconsole.finance.hdpay.HdPayHostedDepositService;
import ffdd.opsconsole.finance.hdpay.HdPayCreateRejectedException;
import ffdd.opsconsole.finance.dto.AppVietQrIntentCancelRequest;
import ffdd.opsconsole.finance.dto.AppVietQrIntentCreateRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.GatewaySecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/app")
@RequiredArgsConstructor
@Slf4j
public class AppVietQrIntentController {
    private static final Set<String> SAFE_CREATE_UNAVAILABLE_CODES = Set.of(
            "HDPAY_CONFIGURATION_INCOMPLETE", "PAYMENT_CONFIG_UNAVAILABLE", "FX_QUOTE_UNAVAILABLE",
            "VIETQR_CHANNEL_UNAVAILABLE", "VIETQR_CHANNEL_CONFIG_INVALID", "VIETQR_RUNTIME_PROFILE_INVALID",
            "VIETQR_SANDBOX_RUN_ID_REQUIRED", "VIETQR_SANDBOX_CONFIG_UNAVAILABLE", "VIETQR_BANK_RAIL_UNAVAILABLE",
            "VIETQR_INTENT_READ_AFTER_WRITE_FAILED", "VIETQR_PAYMENT_RAIL_INVALID", "VIETQR_TIMESTAMP_INVALID",
            "HDPAY_ORDER_RESERVATION_LOST", "HDPAY_ORDER_STATE_CONFLICT", "HDPAY_ORDER_READ_AFTER_WRITE_FAILED",
            "HDPAY_ORDER_SUBMISSION_STATE_CONFLICT", "HDPAY_ORDER_SUBMISSION_UNKNOWN", "HDPAY_ORDER_STATE_INVALID",
            "HDPAY_ORDER_AMOUNT_INVALID");
    private final HdPayHostedDepositService service;
    private final GatewaySecurityProperties gatewaySecurity;

    @ExceptionHandler(HdPayCreateRejectedException.class)
    public ApiResult<Map<String, Object>> createRejected(
            HdPayCreateRejectedException ex, HttpServletResponse response) {
        response.setStatus(ex.getCode());
        return ApiResult.fail(ex.getCode(), ex.getMessage(), Map.of("providerReason", ex.providerReason()));
    }

    @GetMapping("/payments/config")
    public ApiResult<Map<String, Object>> paymentConfig(Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.paymentConfig();
    }

    @GetMapping("/payments/fx-quote")
    public ApiResult<Map<String, Object>> fxQuote(
            @RequestParam String fiat,
            @RequestParam String asset,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.fxQuote(fiat, asset);
    }

    @PostMapping("/deposits/vietqr/intents")
    public ApiResult<Map<String, Object>> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) AppVietQrIntentCreateRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        Long userId = userId(authentication);
        try {
            return userId == null ? forbidden()
                    : service.create(
                            userId,
                            idempotencyKey,
                            request == null ? null : request.usdtAmount(),
                            clientIp(httpRequest));
        } catch (BizException failure) {
            if (failure.getCode() == 503 && httpRequest != null
                    && "POST".equals(httpRequest.getMethod())
                    && "/api/app/deposits/vietqr/intents".equals(httpRequest.getRequestURI())) {
                String reason = failure.getMessage();
                log.warn("event=VIETQR_INTENT_CREATE_UNAVAILABLE phase=CONTROLLER code={}",
                        reason != null && SAFE_CREATE_UNAVAILABLE_CODES.contains(reason) ? reason : "UNCLASSIFIED_503");
            }
            throw failure;
        }
    }

    @GetMapping("/deposits/vietqr/intents")
    public ApiResult<Map<String, Object>> list(
            @RequestParam(required = false) Integer limit,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.list(userId, limit);
    }

    @GetMapping("/deposits/vietqr/intents/{intentNo}")
    public ApiResult<Map<String, Object>> get(
            @PathVariable String intentNo,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.get(userId, intentNo);
    }

    @GetMapping("/deposits/vietqr/receipts")
    public ApiResult<Map<String, Object>> receipts(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.receipts(userId, limit, offset);
    }

    @PostMapping("/deposits/vietqr/intents/{intentNo}/cancel")
    public ApiResult<Map<String, Object>> cancel(
            @PathVariable String intentNo,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) AppVietQrIntentCancelRequest request,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden()
                : service.cancel(
                        userId, intentNo, idempotencyKey,
                        request == null ? null : request.expectedVersion());
    }

    private Long userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal() == null
                || !(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(String.valueOf(details.get("subjectType")))) {
            return null;
        }
        try {
            long value = Long.parseLong(String.valueOf(authentication.getPrincipal()));
            return value > 0 ? value : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private ApiResult<Map<String, Object>> forbidden() {
        return ApiResult.fail(403, "USER_SUBJECT_REQUIRED");
    }

    private String clientIp(HttpServletRequest request) {
        return ffdd.opsconsole.finance.hdpay.HdPayClientIp.resolve(request, gatewaySecurity);
    }
}
