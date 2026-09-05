package ffdd.opsconsole.finance.hdpay;

import ffdd.opsconsole.finance.application.AppVietQrIntentService;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class HdPayHostedDepositService {
    static final String SUBMISSION_RESERVED_MARKER = "_hdpaySubmissionReserved";
    private final AppVietQrIntentService legacy;
    private final HdPayProperties properties;
    private final HdPayGateway gateway;
    private final HdPayOrderMapper mapper;

    public ApiResult<Map<String, Object>> paymentConfig() {
        ApiResult<Map<String, Object>> result = legacy.paymentConfig();
        if (!properties.providerMode()) return result;
        Map<String, Object> root = copy(result.getData());
        Map<String, Object> vietQr = copy(map(root.get("vietQr")));
        vietQr.put("paymentMode", "hosted");
        vietQr.put("enabled", Boolean.TRUE.equals(vietQr.get("enabled")) && properties.ready());
        root.put("vietQr", vietQr);
        return ApiResult.ok(root);
    }

    public ApiResult<Map<String, Object>> fxQuote(String fiat, String asset) {
        return legacy.fxQuote(fiat, asset);
    }

    public ApiResult<Map<String, Object>> create(
            Long userId, String idempotencyKey, BigDecimal amount, String clientIp) {
        if (!properties.providerMode()) return legacy.create(userId, idempotencyKey, amount);
        requireReady();
        ApiResult<Map<String, Object>> canonical = legacy.create(userId, idempotencyKey, amount);
        return submitPrepared(canonical.getData(), clientIp);
    }

    public ApiResult<Map<String, Object>> submitPrepared(
            Map<String, Object> canonical, String clientIp) {
        requireReady();
        Map<String, Object> view = copy(canonical);
        if ("COMMERCE_ORDER".equalsIgnoreCase(text(view.get("settlementTargetType")))
                || "COMMERCE_ORDER".equalsIgnoreCase(text(view.get("settlement_target_type")))
                || !text(view.get("targetOrderNo")).isEmpty()
                || !text(view.get("target_order_no")).isEmpty()) {
            // HDPay is a wallet top-up rail only. Fail before reading provider
            // state or making a network call so no internal caller can revive
            // the retired direct-commerce path.
            throw new BizException(409, "HDPAY_COMMERCE_DIRECT_PAYMENT_RETIRED");
        }
        boolean reservedByCommerceTransaction = Boolean.TRUE.equals(
                view.remove(SUBMISSION_RESERVED_MARKER));
        String merchantOrderId = text(view.get("intentNo"));
        BigDecimal amountVnd = decimal(view.get("vndAmount"));
        view = hostedView(view);
        Map<String, Object> existing = mapper.findByMerchantOrderId(merchantOrderId);
        if (reservedByCommerceTransaction) {
            if (existing == null || !"SUBMIT_UNKNOWN".equals(text(existing.get("submissionStatus")))) {
                throw new BizException(503, "HDPAY_ORDER_RESERVATION_LOST");
            }
            if (!futureInstant(view.get("expiresAt"))) {
                if (mapper.markRejected(
                        merchantOrderId, "HDPAY_INTENT_EXPIRED_BEFORE_SUBMISSION") != 1) {
                    throw new BizException(503, "HDPAY_ORDER_STATE_CONFLICT");
                }
                throw new BizException(409, "HDPAY_ORDER_NOT_PAYABLE");
            }
            return submitPending(view, merchantOrderId, amountVnd, clientIp);
        }
        if (existing != null) return ApiResult.ok(resolveOrOverlay(view, existing));

        int inserted = mapper.insertPending(
                merchantOrderId,
                amountVnd,
                submissionRequestHash(merchantOrderId, amountVnd));
        if (inserted != 1) {
            Map<String, Object> concurrent = mapper.findByMerchantOrderId(merchantOrderId);
            if (concurrent == null) throw new BizException(503, "HDPAY_ORDER_READ_AFTER_WRITE_FAILED");
            return ApiResult.ok(overlay(view, concurrent, true));
        }
        if (mapper.authorizeSubmissionIfIntentPayable(merchantOrderId) != 1) {
            throw new BizException(503, "HDPAY_ORDER_SUBMISSION_STATE_CONFLICT");
        }
        return submitPending(view, merchantOrderId, amountVnd, clientIp);
    }

    private ApiResult<Map<String, Object>> submitPending(
            Map<String, Object> view,
            String merchantOrderId,
            BigDecimal amountVnd,
            String clientIp) {
        try {
            HdPayGateway.PayPage page = gateway.createPayOrder(
                    new HdPayGateway.CreatePayOrder(merchantOrderId, amountVnd, clientIp));
            if (mapper.markCreated(merchantOrderId, page.url()) != 1) {
                throw new BizException(503, "HDPAY_ORDER_STATE_CONFLICT");
            }
            view.put("paymentMode", "hosted");
            view.put("paymentUrl", page.url());
            view.put("providerStatus", "created");
            return ApiResult.ok(view);
        } catch (HdPayGatewayException ex) {
            String error = safeError(ex.getMessage());
            if (ex.ambiguous()) {
                mapper.markSubmitUnknown(merchantOrderId, error);
                throw new BizException(503, "HDPAY_ORDER_SUBMISSION_UNKNOWN");
            }
            mapper.markRejected(merchantOrderId, error);
            throw new BizException(502, "HDPAY_ORDER_CREATE_REJECTED");
        }
    }

    public ApiResult<Map<String, Object>> get(Long userId, String intentNo) {
        ApiResult<Map<String, Object>> result = legacy.get(userId, intentNo);
        return properties.providerMode()
                ? ApiResult.ok(overlay(result.getData(), mapper.findByMerchantOrderId(intentNo), false))
                : result;
    }

    public ApiResult<Map<String, Object>> list(Long userId, Integer limit) {
        ApiResult<Map<String, Object>> result = legacy.list(userId, limit);
        if (!properties.providerMode()) return result;
        Map<String, Object> root = copy(result.getData());
        Object rawItems = root.get("items");
        if (rawItems instanceof List<?> rows) {
            List<Map<String, Object>> items = new java.util.ArrayList<>();
            for (Object raw : rows) {
                Map<String, Object> item = copy(map(raw));
                items.add(overlay(item, mapper.findByMerchantOrderId(text(item.get("intentNo"))), false));
            }
            root.put("items", items);
        }
        return ApiResult.ok(root);
    }

    public ApiResult<Map<String, Object>> receipts(Long userId, Integer limit, Integer offset) {
        return legacy.receipts(userId, limit, offset);
    }

    public ApiResult<Map<String, Object>> cancel(
            Long userId, String intentNo, String idempotencyKey, Long expectedVersion) {
        if (properties.providerMode()) {
            Map<String, Object> provider = mapper.findByMerchantOrderId(intentNo);
            if (provider != null && Set.of("PENDING", "CREATED", "SUBMIT_UNKNOWN")
                    .contains(text(provider.get("submissionStatus")))) {
                // HDPay exposes no order-close/refund endpoint. A locally-cancelled
                // order could still be paid on the provider page, so keep it open
                // for reconciliation instead of creating split-brain state.
                throw new BizException(409, "HDPAY_PROVIDER_ORDER_NOT_CANCELLABLE");
            }
            Map<String, Object> canonical = legacy.get(userId, intentNo).getData();
            if (canonical != null && !text(canonical.get("targetOrderNo")).isEmpty()) {
                throw new BizException(409, "HDPAY_COMMERCE_INTENT_NOT_CANCELLABLE");
            }
        }
        return legacy.cancel(userId, intentNo, idempotencyKey, expectedVersion);
    }

    private Map<String, Object> overlay(
            Map<String, Object> canonical, Map<String, Object> provider, boolean failOnUnusable) {
        Map<String, Object> result = hostedView(canonical);
        result.put("paymentMode", "hosted");
        if (provider == null) {
            result.put("providerStatus", "not_submitted");
            return result;
        }
        String status = text(provider.get("submissionStatus"));
        result.put("providerStatus", status.toLowerCase(Locale.ROOT));
        String url = text(provider.get("paymentUrl"));
        if ("CREATED".equals(status)
                && "awaiting_payment".equals(text(canonical.get("status")))
                && properties.isTrustedPaymentPage(url)) {
            result.put("paymentUrl", url);
            return result;
        }
        if ("CREATED".equals(status) && !failOnUnusable) return result;
        if (!failOnUnusable && ("SUBMIT_UNKNOWN".equals(status)
                || "PENDING".equals(status)
                || "REJECTED".equals(status))) {
            return result;
        }
        if ("SUBMIT_UNKNOWN".equals(status)) {
            throw new BizException(503, "HDPAY_ORDER_SUBMISSION_UNKNOWN");
        }
        if ("PENDING".equals(status)) {
            throw new BizException(409, "HDPAY_ORDER_SUBMISSION_IN_PROGRESS");
        }
        if ("REJECTED".equals(status)) {
            throw new BizException(502, "HDPAY_ORDER_CREATE_REJECTED");
        }
        throw new BizException(503, "HDPAY_ORDER_STATE_INVALID");
    }

    private Map<String, Object> resolveOrOverlay(
            Map<String, Object> canonical, Map<String, Object> provider) {
        if (!"SUBMIT_UNKNOWN".equals(text(provider.get("submissionStatus")))) {
            return overlay(canonical, provider, true);
        }
        try {
            HdPayGateway.PayOrder resolved = gateway.queryPayOrder(text(provider.get("merchantOrderId")));
            BigDecimal expected = decimal(provider.get("amountVnd"));
            if (resolved.transAmt().compareTo(expected) != 0) {
                throw new BizException(503, "HDPAY_ORDER_SUBMISSION_UNKNOWN");
            }
            if (resolved.orderStatus() != 1) {
                mapper.observeSubmitUnknownTerminal(
                        resolved.merchantOrderId(),
                        resolved.providerOrderId(),
                        resolved.orderStatus(),
                        "HDPAY_QUERY_STATUS_" + resolved.orderStatus());
                throw new BizException(409, "HDPAY_ORDER_NOT_PAYABLE");
            }
            if (resolved.appLink().isEmpty()) {
                throw new BizException(503, "HDPAY_ORDER_SUBMISSION_UNKNOWN");
            }
            if (mapper.resolveSubmitUnknown(
                    resolved.merchantOrderId(),
                    resolved.providerOrderId(),
                    resolved.orderStatus(),
                    resolved.appLink()) != 1) {
                throw new BizException(503, "HDPAY_ORDER_STATE_CONFLICT");
            }
            return overlay(canonical, Map.of(
                    "merchantOrderId", resolved.merchantOrderId(),
                    "submissionStatus", "CREATED",
                    "paymentUrl", resolved.appLink()), true);
        } catch (HdPayGatewayException ex) {
            throw new BizException(503, "HDPAY_ORDER_SUBMISSION_UNKNOWN");
        }
    }

    private void requireReady() {
        if (!properties.ready()) throw new BizException(503, "HDPAY_CONFIGURATION_INCOMPLETE");
    }

    private Map<String, Object> copy(Map<String, Object> value) {
        return value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
    }

    private Map<String, Object> hostedView(Map<String, Object> canonical) {
        Map<String, Object> result = copy(canonical);
        result.remove(SUBMISSION_RESERVED_MARKER);
        // The provider page owns transfer instructions. Do not expose the
        // compatibility rail's internal account allocation or matching memo.
        result.remove("bankAccount");
        result.remove("memoCode");
        result.remove("qrPayload");
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) return decimal;
        try {
            return new BigDecimal(text(value));
        } catch (NumberFormatException ex) {
            throw new BizException(503, "HDPAY_ORDER_AMOUNT_INVALID");
        }
    }

    private boolean futureInstant(Object value) {
        try {
            return java.time.Instant.parse(text(value)).isAfter(java.time.Instant.now());
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private String safeError(String value) {
        String candidate = text(value);
        return candidate.matches("[A-Z0-9_]{1,64}") ? candidate : "HDPAY_PROVIDER_ERROR";
    }

    static String submissionRequestHash(String merchantOrderId, BigDecimal amountVnd) {
        return sha256Static(merchantOrderId + "|" + amountVnd.toPlainString() + "|BANKQR|VN");
    }

    private static String sha256Static(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("HDPAY_HASH_ALGORITHM_MISSING", ex);
        }
    }
}
