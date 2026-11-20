package ffdd.opsconsole.device.application;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ffdd.opsconsole.device.dto.E2TaskPricingUpdateRequest;
import ffdd.opsconsole.platform.domain.AuditLockTarget;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.platform.dto.AuditOperationProposalRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.SuperAdminAuthorization;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class E2TaskPricingApproval {
    public static final String MAKER_AUTHORITY = "device_e2_write";
    static final String LIFECYCLE_LOCK = "e2_task_lifecycle";
    private static final String LIFECYCLE_OP = "e2_task_lifecycle_change";
    private static final Set<String> LEGACY_LIFECYCLE_OPS = Set.of("e2_task_create", "e2_task_update", "e2_task_delete");
    private static final Set<String> UPSERT_FIELDS = Set.of("action", "taskId", "name", "price", "unit", "requirement",
            "saturation", "status", "taskClass", "model", "minReward", "maxReward", "minVram", "killInit",
            "selectionWeight", "expectedDurationMinSec", "expectedDurationMaxSec", "executionTimeoutSec",
            "runtimeKey", "validatorKey", "workloadConfigJson");
    private final ObjectMapper objectMapper;
    private final SuperAdminAuthorization superAdmins;
    private final OpsDeviceService devices;

    public static boolean supports(AuditReplayCommand command) {
        return isLifecycle(command)
                || command != null && "E".equals(command.domain()) && "e2_task_pricing_change".equals(command.op());
    }

    public static boolean isLifecycle(AuditReplayCommand command) {
        return command != null && "E".equals(command.domain()) && LIFECYCLE_OP.equals(command.op());
    }

    public boolean shouldBindLifecycle(AuditReplayCommand command) {
        return isLifecycle(command) || command != null && "E".equals(command.domain()) && command.op() != null
                && LEGACY_LIFECYCLE_OPS.contains(command.op())
                && hasAuthority(SecurityContextHolder.getContext().getAuthentication(), MAKER_AUTHORITY);
    }

    public boolean canPropose(AuditReplayCommand command) {
        return supports(command) && hasAuthority(SecurityContextHolder.getContext().getAuthentication(), MAKER_AUTHORITY);
    }

    public AuditReplayCommand bindMaker(AuditReplayCommand command, Long adminId) {
        if (adminId == null) throw new BizException(403, "E2_TASK_PRICING_MAKER_REQUIRED");
        return new AuditReplayCommand(command.domain(), command.op(),
                Map.of("values", values(command), "makerAdminId", adminId));
    }

    public AuditReplayCommand bindLifecycle(AuditOperationProposalRequest request, Long adminId) {
        AuditReplayCommand command = request.command();
        boolean legacy = !isLifecycle(command);
        if (!legacy && !canPropose(command)) throw new BizException(403, "E2_TASK_PRICING_MAKER_FORBIDDEN");
        if (adminId == null) throw new BizException(403, "E2_TASK_PRICING_MAKER_REQUIRED");
        Map<String, Object> fields = new LinkedHashMap<>();
        if (legacy) {
            if (command.params() != null) fields.putAll(command.params());
            fields.keySet().removeAll(Set.of("reason", "operator", "enabled"));
            fields.put("action", command.op().substring("e2_task_".length()));
        } else {
            values(command).forEach((key, value) -> {
                if (!(key instanceof String field)) throw invalidLifecycle();
                fields.put(field, value);
            });
            String action = text(fields.get("action"));
            Set<String> allowed = "delete".equals(action) ? Set.of("action", "taskId") : UPSERT_FIELDS;
            if (!Set.of("create", "update", "delete").contains(action) || !allowed.containsAll(fields.keySet())
                    || fields.containsKey("taskId") && !(fields.get("taskId") instanceof String)
                    || "create".equals(action) != text(fields.get("taskId")).isEmpty()) throw invalidLifecycle();
        }
        // Existing A2 directory clients send JSON numbers. Bind them as decimal text before the ticket round trip.
        for (String field : List.of("minReward", "maxReward", "price", "saturation")) {
            fields.computeIfPresent(field, (key, value) -> value.toString());
        }
        var result = devices.previewTaskLifecycleApproval(fields, request.reason());
        if (result.getCode() != 0) {
            if (legacy && "E2_TASK_PRICING_APPROVAL_NOT_REQUIRED".equals(result.getMessage())) return command;
            throw new BizException(result.getCode(), result.getMessage());
        }
        var review = result.getData();
        AuditLockTarget target = review.target();
        AuditLockTarget supplied = request.target();
        boolean matches = target.equals(supplied) && target.id().equals(text(request.obj()));
        if (legacy && !matches) {
            if ("create".equals(fields.get("action"))) {
                matches = text(fields.get("name")).equals(text(request.obj()))
                        && (supplied == null || "E".equals(supplied.domain()) && "device_task".equals(supplied.type())
                                && text(supplied.id()).isEmpty());
            } else {
                matches = supplied != null && "E".equals(supplied.domain()) && "device_task".equals(supplied.type())
                        && target.id().equalsIgnoreCase(text(supplied.id()))
                        && target.id().equalsIgnoreCase(text(request.obj()));
            }
        }
        if (!matches || !"E2".equals(text(request.sourceDomain())) || request.targets() != null && !request.targets().isEmpty()) {
            throw new BizException(403, "A2_BUSINESS_CONTEXT_MISMATCH");
        }
        return new AuditReplayCommand("E", LIFECYCLE_OP, Map.of("values", fields, "makerAdminId", adminId,
                "targetId", target.id(), "revision", review.revision(), "beforeReview", review.before(),
                "afterReview", review.after()));
    }

    public ApiResult<Void> validateChecker(AuditReplayCommand command, Long adminId) {
        Object maker = command.params() == null ? null : command.params().get("makerAdminId");
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(maker instanceof Number) || adminId == null || !superAdmins.isSuperAdmin(authentication)
                || !hasAuthority(authentication, "platform_a2_operation_approve")) {
            return ApiResult.fail(403, "E2_TASK_PRICING_APPROVAL_FORBIDDEN");
        }
        return String.valueOf(adminId).equals(String.valueOf(maker))
                ? ApiResult.fail(403, "A2_MAKER_CHECKER_REQUIRED") : ApiResult.ok();
    }

    public ApiResult<OpsDeviceService.E2TaskPricingReview> preview(AuditReplayCommand command, String reason) {
        if (isLifecycle(command)) {
            return ApiResult.ok(new OpsDeviceService.E2TaskPricingReview(target(command),
                    boundedReview((String) command.params().get("beforeReview")),
                    boundedReview((String) command.params().get("afterReview"))));
        }
        return devices.previewTaskPricingApproval(request(command, reason));
    }

    public static AuditLockTarget target(AuditReplayCommand command) {
        return command.params() != null && command.params().get("targetId") instanceof String id && !id.isBlank()
                ? new AuditLockTarget("E", isLifecycle(command) ? LIFECYCLE_LOCK : "e2_task_pricing", id) : null;
    }

    public ApiResult<?> replay(AuditReplayCommand command, AuditReplayContext context) {
        AuditLockTarget target = target(command);
        if (target == null || !(command.params().get("proposalReason") instanceof String reason)) {
            return ApiResult.fail(422, "E2_TASK_PRICING_PROPOSAL_INVALID");
        }
        if (isLifecycle(command)) {
            if (!(command.params().get("revision") instanceof String revision)) {
                return ApiResult.fail(422, "E2_TASK_PRICING_PROPOSAL_INVALID");
            }
            Map<String, Object> fields = new LinkedHashMap<>();
            values(command).forEach((key, value) -> fields.put((String) key, value));
            return devices.executeApprovedTaskLifecycle(context.idempotencyKey(), fields, reason, revision, target);
        }
        return devices.executeApprovedTaskPricing(context.idempotencyKey(), request(command, reason), target);
    }

    private static BizException invalidLifecycle() {
        return new BizException(422, "E2_TASK_PRICING_PROPOSAL_INVALID");
    }

    private static String text(Object raw) {
        return raw instanceof String value ? value.trim() : "";
    }

    private static String boundedReview(String review) {
        return review.length() <= 128 ? review : review.substring(0, 127) + "…";
    }

    private static Map<?, ?> values(AuditReplayCommand command) {
        if (command.params() == null || !(command.params().get("values") instanceof Map<?, ?> values)
                || values.isEmpty() || values.containsKey("reason") || values.containsKey("operator")
                || values.containsKey("enabled")) {
            throw new BizException(422, "E2_TASK_PRICING_PROPOSAL_INVALID");
        }
        for (String field : List.of("minReward", "maxReward", "queueSaturation")) {
            Object value = values.get(field);
            if (value instanceof Double || value instanceof Float) {
                throw new BizException(422, "E2_TASK_PRICING_DECIMAL_STRING_REQUIRED");
            }
        }
        return values;
    }

    private E2TaskPricingUpdateRequest request(AuditReplayCommand command, String reason) {
        ObjectNode payload = objectMapper.valueToTree(values(command));
        payload.put("reason", reason);
        try {
            return objectMapper.readerFor(E2TaskPricingUpdateRequest.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT).readValue(payload);
        } catch (IOException invalid) {
            throw new BizException(422, "E2_TASK_PRICING_PROPOSAL_INVALID");
        }
    }

    private static boolean hasAuthority(Authentication authentication, String authority) {
        return authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream().anyMatch(grant -> authority.equals(grant.getAuthority()));
    }
}
