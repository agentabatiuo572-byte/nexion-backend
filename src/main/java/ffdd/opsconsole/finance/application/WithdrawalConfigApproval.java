package ffdd.opsconsole.finance.application;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ffdd.opsconsole.finance.dto.PayoutVndConfigUpdateRequest;
import ffdd.opsconsole.finance.dto.WithdrawalLimitsUpdateRequest;
import ffdd.opsconsole.platform.domain.AuditLockTarget;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.SuperAdminAuthorization;
import java.io.IOException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** A2's two versioned withdrawal-configuration commands. */
@Component
@RequiredArgsConstructor
public class WithdrawalConfigApproval {
    private static final String D5 = "d5_withdrawal_limits";
    private static final String D7 = "d7_payout_vnd_config";
    private static final String MAKER = "makerAdminId";
    private final ObjectMapper objectMapper;
    private final D5WithdrawalAuthorization d5Authorization;
    private final SuperAdminAuthorization superAdmins;
    private final OpsFinanceService finance;
    private final PayoutVndConfigService payoutVnd;

    public static boolean supports(AuditReplayCommand command) {
        return command != null && "D".equals(command.domain())
                && (D5.equals(command.op()) || D7.equals(command.op()));
    }

    public static String source(AuditReplayCommand command) {
        return D5.equals(command.op()) ? "D5" : "D7";
    }

    public static AuditLockTarget target(AuditReplayCommand command) {
        return new AuditLockTarget("D", D5.equals(command.op()) ? "withdrawal_limits" : "payout_vnd_config", "current");
    }

    public AuditReplayCommand bindMaker(AuditReplayCommand command, Long adminId) {
        if (adminId == null) throw new BizException(403, "D_WITHDRAWAL_CONFIG_MAKER_REQUIRED");
        return new AuditReplayCommand(command.domain(), command.op(), Map.of(
                "values", values(command), MAKER, adminId));
    }

    public ApiResult<Void> validateMaker(AuditReplayCommand command) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean permitted;
        if (D5.equals(command.op())) {
            var request = request(command, null, WithdrawalLimitsUpdateRequest.class);
            permitted = !request.hasPhaseFields() && d5Authorization.canUpdateLimits(authentication, request);
        } else {
            var request = request(command, null, PayoutVndConfigUpdateRequest.class);
            permitted = hasAuthority(authentication, "finance_d7_manage")
                    && (!Boolean.TRUE.equals(request.forceInverted())
                        || hasAuthority(authentication, "finance_d7_force_inverted"));
        }
        return permitted ? ApiResult.ok() : ApiResult.fail(403, "D_WITHDRAWAL_CONFIG_MAKER_FORBIDDEN");
    }

    public ApiResult<Void> validateChecker(AuditReplayCommand command, Long adminId, boolean executeChange) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object maker = command.params() == null ? null : command.params().get(MAKER);
        if (!(maker instanceof Number) || adminId == null || !superAdmins.isSuperAdmin(authentication)
                || !hasAuthority(authentication, "platform_a2_operation_approve")) {
            return ApiResult.fail(403, "D_WITHDRAWAL_CONFIG_APPROVAL_FORBIDDEN");
        }
        if (String.valueOf(adminId).equals(String.valueOf(maker))) {
            return ApiResult.fail(403, "A2_MAKER_CHECKER_REQUIRED");
        }
        if (executeChange && D7.equals(command.op()) && Boolean.TRUE.equals(
                request(command, null, PayoutVndConfigUpdateRequest.class).forceInverted())
                && !hasAuthority(authentication, "finance_d7_force_inverted")) {
            return ApiResult.fail(403, "D_WITHDRAWAL_CONFIG_APPROVAL_FORBIDDEN");
        }
        return ApiResult.ok();
    }

    public ApiResult<Map<String, Object>> preview(AuditReplayCommand command, String reason) {
        return D5.equals(command.op())
                ? finance.previewWithdrawalLimits(request(command, reason, WithdrawalLimitsUpdateRequest.class))
                : payoutVnd.previewUpdate(request(command, reason, PayoutVndConfigUpdateRequest.class));
    }

    public ApiResult<?> replay(AuditReplayCommand command, AuditReplayContext ctx) {
        if (!(command.params().get("proposalReason") instanceof String reason)) {
            return ApiResult.fail(422, "D_WITHDRAWAL_CONFIG_PROPOSAL_INVALID");
        }
        return D5.equals(command.op())
                ? finance.replayWithdrawalLimits(ctx.idempotencyKey(),
                    request(command, reason, WithdrawalLimitsUpdateRequest.class))
                : payoutVnd.replayUpdate(request(command, reason, PayoutVndConfigUpdateRequest.class));
    }

    private static Map<?, ?> values(AuditReplayCommand command) {
        if (command.params() == null || !(command.params().get("values") instanceof Map<?, ?> values)
                || values.isEmpty() || values.containsKey("reason") || values.containsKey("operator")) {
            throw new BizException(422, "D_WITHDRAWAL_CONFIG_PROPOSAL_INVALID");
        }
        return values;
    }

    private <T> T request(AuditReplayCommand command, String reason, Class<T> type) {
        ObjectNode payload = objectMapper.valueToTree(values(command));
        if (reason != null) payload.put("reason", reason);
        try {
            return objectMapper.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .readValue(payload);
        } catch (IOException ex) {
            throw new BizException(422, "D_WITHDRAWAL_CONFIG_PROPOSAL_INVALID");
        }
    }

    private static boolean hasAuthority(Authentication authentication, String authority) {
        return authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream().anyMatch(grant -> authority.equals(grant.getAuthority()));
    }
}
