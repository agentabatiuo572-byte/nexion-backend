package ffdd.opsconsole.device.application;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ffdd.opsconsole.device.dto.E2TaskPricingUpdateRequest;
import ffdd.opsconsole.platform.domain.AuditLockTarget;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.SuperAdminAuthorization;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class E2TaskPricingApproval {
    public static final String MAKER_AUTHORITY = "device_e2_write";
    private final ObjectMapper objectMapper;
    private final SuperAdminAuthorization superAdmins;
    private final OpsDeviceService devices;

    public static boolean supports(AuditReplayCommand command) {
        return command != null && "E".equals(command.domain()) && "e2_task_pricing_change".equals(command.op());
    }

    public boolean canPropose(AuditReplayCommand command) {
        return supports(command) && hasAuthority(SecurityContextHolder.getContext().getAuthentication(), MAKER_AUTHORITY);
    }

    public AuditReplayCommand bindMaker(AuditReplayCommand command, Long adminId) {
        if (adminId == null) throw new BizException(403, "E2_TASK_PRICING_MAKER_REQUIRED");
        return new AuditReplayCommand(command.domain(), command.op(),
                Map.of("values", values(command), "makerAdminId", adminId));
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
        return devices.previewTaskPricingApproval(request(command, reason));
    }

    public static AuditLockTarget target(AuditReplayCommand command) {
        return command.params() != null && command.params().get("targetId") instanceof String id && !id.isBlank()
                ? new AuditLockTarget("E", "e2_task_pricing", id) : null;
    }

    public ApiResult<?> replay(AuditReplayCommand command, AuditReplayContext context) {
        AuditLockTarget target = target(command);
        if (target == null || !(command.params().get("proposalReason") instanceof String reason)) {
            return ApiResult.fail(422, "E2_TASK_PRICING_PROPOSAL_INVALID");
        }
        return devices.executeApprovedTaskPricing(context.idempotencyKey(), request(command, reason), target);
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
