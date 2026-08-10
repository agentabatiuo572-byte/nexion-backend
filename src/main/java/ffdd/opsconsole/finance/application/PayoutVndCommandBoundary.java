package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.finance.dto.PayoutVndChannelUpdateRequest;
import ffdd.opsconsole.finance.dto.PayoutVndConfigUpdateRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PayoutVndCommandBoundary {
    private final AdminIdempotencyService idempotency;

    @SuppressWarnings({"rawtypes", "unchecked"})
    public ApiResult<Map<String, Object>> execute(
            String operation,
            String idempotencyKey,
            Object request,
            Supplier<ApiResult<Map<String, Object>>> action) {
        String normalizedOperation = operation == null
                ? "UNKNOWN"
                : operation.trim().toUpperCase().replaceAll("[^A-Z0-9_-]", "_");
        String requestHash = sha256(normalizedOperation + "|" + canonicalRequest(request));
        return (ApiResult<Map<String, Object>>) idempotency.execute(
                "FINANCE:D7:" + normalizedOperation,
                idempotencyKey,
                requestHash,
                ApiResult.class,
                (Supplier) action);
    }

    private String canonicalRequest(Object request) {
        if (request instanceof PayoutVndConfigUpdateRequest value) {
            return String.join("|",
                    decimal(value.sellSpreadPct()),
                    String.valueOf(value.quoteTtlMinWithdraw()),
                    decimal(value.requoteTolerancePct()),
                    decimal(value.feeRatePct()),
                    decimal(value.feeMinUsd()),
                    decimal(value.feeMaxUsd()),
                    decimal(value.minAmountUsd()),
                    decimal(value.maxAmountUsd()),
                    String.valueOf(value.expectedVersion()),
                    text(value.reason()),
                    String.valueOf(Boolean.TRUE.equals(value.forceInverted())));
        }
        if (request instanceof PayoutVndChannelUpdateRequest value) {
            return String.join("|",
                    String.valueOf(value.enabled()),
                    String.valueOf(value.expectedVersion()),
                    text(value.reason()));
        }
        return String.valueOf(request);
    }

    private String decimal(BigDecimal value) {
        return value == null ? "null" : value.stripTrailingZeros().toPlainString();
    }

    private String text(String value) {
        return value == null ? "null" : value.trim();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
