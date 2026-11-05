package ffdd.opsconsole.treasury.application;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ffdd.opsconsole.platform.domain.AuditLockTarget;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.risk.application.OpsRiskRadarService;
import ffdd.opsconsole.risk.dto.B5BankRunThresholdRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.SuperAdminAuthorization;
import ffdd.opsconsole.treasury.dto.TreasuryThresholdRequest;
import java.io.IOException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** A2 commands for lowering the coverage gate or delaying automatic withdrawal stoppage. */
@Component
@RequiredArgsConstructor
public class TreasuryThresholdApproval {
    private static final String B1 = "b1_coverage_thresholds";
    private static final String B5 = "b5_bankrun_thresholds";
    private final ObjectMapper objectMapper;
    private final SuperAdminAuthorization superAdmins;
    private final OpsTreasuryService treasury;
    private final OpsRiskRadarService radar;

    public static boolean supports(AuditReplayCommand command) {
        return command != null && "B".equals(command.domain())
                && (B1.equals(command.op()) || B5.equals(command.op()));
    }

    public static String source(AuditReplayCommand command) {
        return B1.equals(command.op()) ? "B1" : "B5";
    }

    public static AuditLockTarget target(AuditReplayCommand command) {
        return new AuditLockTarget("B", B1.equals(command.op()) ? "coverage_thresholds" : "bankrun_thresholds", "current");
    }

    public AuditReplayCommand bindMaker(AuditReplayCommand command, Long adminId) {
        if (adminId == null) throw new BizException(403, "B_THRESHOLD_MAKER_REQUIRED");
        return new AuditReplayCommand(command.domain(), command.op(),
                Map.of("values", values(command), "makerAdminId", adminId));
    }

    public ApiResult<Void> validateMaker(AuditReplayCommand command) {
        return canPropose(command)
                ? ApiResult.ok() : ApiResult.fail(403, "B_THRESHOLD_MAKER_FORBIDDEN");
    }

    public boolean canPropose(AuditReplayCommand command) {
        return supports(command) && hasAuthority(SecurityContextHolder.getContext().getAuthentication(), makerAuthority(command));
    }

    public static String makerAuthority(AuditReplayCommand command) {
        return B1.equals(command.op()) ? "overview_b1_redline_write" : "overview_b5_threshold_write";
    }

    public ApiResult<Void> validateChecker(AuditReplayCommand command, Long adminId) {
        Object maker = command.params() == null ? null : command.params().get("makerAdminId");
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(maker instanceof Number) || adminId == null
                || !superAdmins.isSuperAdmin(authentication)
                || !hasAuthority(authentication, "platform_a2_operation_approve")) {
            return ApiResult.fail(403, "B_THRESHOLD_APPROVAL_FORBIDDEN");
        }
        return String.valueOf(adminId).equals(String.valueOf(maker))
                ? ApiResult.fail(403, "A2_MAKER_CHECKER_REQUIRED") : ApiResult.ok();
    }

    public ApiResult<Map<String, Object>> preview(AuditReplayCommand command, String reason) {
        if (B1.equals(command.op())) {
            var request = b1Request(command, reason);
            return treasury.previewThresholdApproval(request, request.expectedVersion());
        }
        return radar.previewThresholdApproval(request(command, reason, B5BankRunThresholdRequest.class));
    }

    public ApiResult<?> replay(AuditReplayCommand command, AuditReplayContext ctx) {
        if (!(command.params().get("proposalReason") instanceof String reason)) {
            return ApiResult.fail(422, "B_THRESHOLD_PROPOSAL_INVALID");
        }
        if (B1.equals(command.op())) {
            var request = b1Request(command, reason);
            return treasury.executeApprovedThresholds(ctx.idempotencyKey(), request, request.expectedVersion());
        }
        return radar.replayThresholdApproval(request(command, reason, B5BankRunThresholdRequest.class));
    }

    private TreasuryThresholdRequest b1Request(AuditReplayCommand command, String reason) {
        var request = request(command, reason, TreasuryThresholdRequest.class);
        if (request.runRiskPct() != null || request.expectedVersion() == null || request.expectedVersion() < 0) {
            throw new BizException(422, "B_THRESHOLD_PROPOSAL_INVALID");
        }
        return request;
    }

    private static Map<?, ?> values(AuditReplayCommand command) {
        if (command.params() == null || !(command.params().get("values") instanceof Map<?, ?> values)
                || values.isEmpty() || values.containsKey("reason") || values.containsKey("operator")) {
            throw new BizException(422, "B_THRESHOLD_PROPOSAL_INVALID");
        }
        return values;
    }

    private <T> T request(AuditReplayCommand command, String reason, Class<T> type) {
        ObjectNode payload = objectMapper.valueToTree(values(command));
        payload.put("reason", reason);
        try {
            return objectMapper.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT).readValue(payload);
        } catch (IOException ex) {
            throw new BizException(422, "B_THRESHOLD_PROPOSAL_INVALID");
        }
    }

    private static boolean hasAuthority(Authentication authentication, String authority) {
        return authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream().anyMatch(grant -> authority.equals(grant.getAuthority()));
    }
}
