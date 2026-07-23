package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** One atomic command boundary for every H1-H7 admin mutation. */
@Service
@RequiredArgsConstructor
public class OpsGrowthCommandBoundary {
    private final AdminIdempotencyService idempotency;
    private final EventOutboxService outbox;

    @SuppressWarnings({"rawtypes", "unchecked"})
    public ApiResult<Map<String, Object>> execute(
            String module,
            String operation,
            String target,
            String idempotencyKey,
            Object request,
            Supplier<ApiResult<Map<String, Object>>> action) {
        String normalizedModule = normalize(module);
        String normalizedOperation = normalize(operation);
        String normalizedTarget = target == null || target.isBlank() ? "GLOBAL" : target.trim();
        String scope = "GROWTH:" + normalizedModule + ":" + normalizedOperation + ":" + normalizedTarget;
        String requestHash = hash(normalizedModule + "|" + normalizedOperation + "|" + normalizedTarget + "|" + String.valueOf(request));
        return (ApiResult<Map<String, Object>>) idempotency.execute(
                scope,
                idempotencyKey,
                requestHash,
                ApiResult.class,
                (Supplier) () -> {
                    ApiResult<Map<String, Object>> result = action.get();
                    if (result != null && result.getCode() == 0) {
                        outbox.publish("GROWTH_COMMAND", normalizedModule + ":" + normalizedTarget,
                                "admin.growth_config_changed", Map.of(
                                        "module_id", normalizedModule,
                                        "operation", normalizedOperation,
                                        "target_id", normalizedTarget,
                                        "idempotency_key", idempotencyKey.trim()));
                    }
                    return result;
                });
    }

    private String normalize(String value) {
        return value == null ? "UNKNOWN" : value.trim().toUpperCase().replaceAll("[^A-Z0-9_-]", "_");
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
