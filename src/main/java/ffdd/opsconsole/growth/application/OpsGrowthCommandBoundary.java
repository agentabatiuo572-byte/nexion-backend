package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.growth.dto.GrowthMissionEditRequest;
import ffdd.opsconsole.growth.dto.GrowthMissionStatusRequest;
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
import org.springframework.dao.PessimisticLockingFailureException;
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
        String scope = commandScope(normalizedModule, normalizedOperation, normalizedTarget, request);
        String requestHash = hash(scope + "|" + String.valueOf(request));
        try {
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
        } catch (PessimisticLockingFailureException ex) {
            if ("H3".equals(normalizedModule) && "QUEST_CONFIG_UPDATE".equals(normalizedOperation)) {
                return ApiResult.fail(422, "QUEST_CONFIG_STALE");
            }
            throw ex;
        }
    }

    private String commandScope(String module, String operation, String target, Object request) {
        if ("H3".equals(module)) {
            String action = switch (operation) {
                case "MISSION_EDIT" -> "EDIT";
                case "MISSION_STATUS" -> "STATUS";
                case "MISSION_ARCHIVE" -> "ARCHIVE";
                case "MISSION_DELETE" -> "DELETE";
                default -> null;
            };
            String kind = request instanceof GrowthMissionEditRequest edit
                    ? edit.taskKind()
                    : request instanceof GrowthMissionStatusRequest status ? status.taskKind() : null;
            if (action != null && kind != null && !kind.isBlank()) {
                return "H3_MISSION:" + action + ":" + normalize(kind) + ":" + target;
            }
        }
        return "GROWTH:" + module + ":" + operation + ":" + target;
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
