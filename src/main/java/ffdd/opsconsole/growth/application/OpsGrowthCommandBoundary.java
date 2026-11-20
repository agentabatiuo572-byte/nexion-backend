package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.growth.dto.GrowthPublicStatsUpdateRequest;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.domain.AuditLockTarget;
import ffdd.opsconsole.platform.mapper.AuditObjectLockMapper;
import ffdd.opsconsole.growth.dto.GrowthMissionEditRequest;
import ffdd.opsconsole.growth.dto.GrowthMissionStatusRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
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
    private final java.util.Optional<ffdd.opsconsole.growth.mapper.GrowthQuestEventMapper> trialSessions;
    private final AuditObjectLockMapper objectLocks;

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
        String requestText = String.valueOf(request);
        // Keep the two older H9 request fingerprints. An explicitly cleared base is its own command.
        if (request instanceof GrowthPublicStatsUpdateRequest h9 && h9.activeJobsBase() == null) {
            requestText = requestText.substring(0, requestText.length() - ", activeJobsBase=null]".length()) + "]";
            if (h9.globeDisplay() == null) {
                requestText = requestText.substring(0, requestText.length() - ", globeDisplay=null]".length()) + "]";
            }
        }
        String requestHash = hash(scope + "|" + requestText);
        Supplier<ApiResult<Map<String, Object>>> commandAction = () -> {
            rejectPendingH4ObjectLock(normalizedModule, normalizedOperation, normalizedTarget);
            ApiResult<Map<String, Object>> result = action.get();
            if (result != null && result.getCode() == 0) {
                outbox.publish("GROWTH_COMMAND", normalizedModule + ":" + normalizedTarget,
                        "admin.growth_config_changed", Map.of(
                                "module_id", normalizedModule, "operation", normalizedOperation,
                                "target_id", normalizedTarget, "idempotency_key", idempotencyKey.trim()));
            }
            return result;
        };
        try {
            if ("H2".equals(normalizedModule)) {
                String actualTarget = normalizedTarget;
                if (java.util.Set.of("TRIAL_SESSION_CANCEL", "TRIAL_SESSION_CHARGE").contains(normalizedOperation)) {
                    actualTarget = trialSessions.map(mapper -> mapper.trialSession(normalizedTarget))
                            .map(row -> (String) row.get("sid")).orElse(normalizedTarget);
                }
                return (ApiResult<Map<String, Object>>) idempotency.executeBoundTargets(scope, idempotencyKey,
                        requestHash, ApiResult.class, "growth_h2_read", java.util.List.of(
                                new ffdd.opsconsole.shared.idempotency.AdminCommandOrigin.Target(
                                        normalizedOperation, "H2", "trial_target", actualTarget)), (Supplier) commandAction);
            }
            return (ApiResult<Map<String, Object>>) idempotency.execute(
                    scope,
                    idempotencyKey,
                    requestHash,
                    ApiResult.class,
                    (Supplier) commandAction);
        } catch (PessimisticLockingFailureException ex) {
            if ("H3".equals(normalizedModule) && "QUEST_CONFIG_UPDATE".equals(normalizedOperation)) {
                return ApiResult.fail(422, "QUEST_CONFIG_STALE");
            }
            throw ex;
        }
    }

    private void rejectPendingH4ObjectLock(String module, String operation, String target) {
        if (!"H4".equals(module) || A2ReplayContext.isReplaying()) return;
        for (AuditLockTarget lockTarget : h4LockTargets(operation, target)) {
            if (objectLocks.countActiveByTarget(lockTarget.domain(), lockTarget.type(), lockTarget.id()) > 0) {
                throw new BizException(409, "OBJECT_LOCKED_BY_A2");
            }
        }
    }

    private List<AuditLockTarget> h4LockTargets(String operation, String target) {
        return switch (operation) {
            case "EVENT_RULE_UPDATE", "EVENT_REWARD_UPDATE", "EVENT_STATUS_UPDATE",
                    "EVENT_LOCALIZED_CONTENT_UPDATE", "EVENT_FEATURED_UPDATE", "EVENT_DELETE" ->
                    List.of(new AuditLockTarget("H", "h4_event_rule", target),
                            new AuditLockTarget("H", "h4_legacy_event", target));
            case "GENESIS_RAFFLE_CREATE", "GENESIS_RAFFLE_UPDATE" ->
                    List.of(new AuditLockTarget("H", "h4_genesis_raffle", target));
            case "WHEEL_GUARD_CREATE", "WHEEL_GUARD_UPDATE" ->
                    List.of(new AuditLockTarget("H", "h4_wheel_guard", target));
            case "WHEEL_TIER_CREATE", "WHEEL_TIER_UPDATE", "WHEEL_TIER_DELETE",
                    "WHEEL_TIER_RETIRE", "WHEEL_PROBABILITIES_UPDATE" ->
                    List.of(new AuditLockTarget("H", "h4_wheel_pool", "ACTIVE_POOL"));
            default -> List.of();
        };
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
