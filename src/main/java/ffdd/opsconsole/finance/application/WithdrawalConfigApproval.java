package ffdd.opsconsole.finance.application;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ffdd.opsconsole.finance.dto.PayoutVndConfigUpdateRequest;
import ffdd.opsconsole.finance.dto.FxQuoteUpdateRequest;
import ffdd.opsconsole.finance.dto.WithdrawalLimitsUpdateRequest;
import ffdd.opsconsole.platform.domain.AuditLockTarget;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.SuperAdminAuthorization;
import ffdd.opsconsole.shared.security.AdminPermissionCache;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Versioned D5/D6/D7 finance-configuration commands share A2's maker/checker flow. */
@Component
@RequiredArgsConstructor
public class WithdrawalConfigApproval {
    private static final String D5 = "d5_withdrawal_limits";
    private static final String D6 = "d6_fx_quote";
    private static final String D7 = "d7_payout_vnd_config";
    private static final String MAKER = "makerAdminId";
    private final ObjectMapper objectMapper;
    private final D5WithdrawalAuthorization d5Authorization;
    private final SuperAdminAuthorization superAdmins;
    private final OpsFinanceService finance;
    private final PayoutVndConfigService payoutVnd;
    private final OpsVietnamPaymentService vietnam;
    private final AdminPermissionCache permissions;
    private final AdminOperatorRoleResolver administrators;

    public static boolean supports(AuditReplayCommand command) {
        return command != null && "D".equals(command.domain())
                && (D5.equals(command.op()) || D6.equals(command.op()) || D7.equals(command.op()));
    }

    public static String source(AuditReplayCommand command) {
        return D5.equals(command.op()) ? "D5" : D6.equals(command.op()) ? "D6" : "D7";
    }

    public static AuditLockTarget target(AuditReplayCommand command) {
        return new AuditLockTarget("D", D5.equals(command.op()) ? "withdrawal_limits"
                : D6.equals(command.op()) ? "fx_quote" : "payout_vnd_config", "current");
    }

    public AuditReplayCommand bindMaker(AuditReplayCommand command, Long adminId) {
        if (adminId == null) throw new BizException(403, "D_WITHDRAWAL_CONFIG_MAKER_REQUIRED");
        return new AuditReplayCommand(command.domain(), command.op(), Map.of(
                "values", values(command), MAKER, adminId));
    }

    public ApiResult<Void> validateMaker(AuditReplayCommand command) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return canMakeProposal(command, authority -> hasAuthority(authentication, authority))
                ? ApiResult.ok() : ApiResult.fail(403, "D_WITHDRAWAL_CONFIG_MAKER_FORBIDDEN");
    }

    public boolean canReadOwnProposal(AuditReplayCommand command, long adminId, Set<String> grants) {
        Object maker = command.params() == null ? null : command.params().get(MAKER);
        return maker instanceof Number && Long.toString(adminId).equals(maker.toString())
                && canMakeProposal(command, grants::contains);
    }

    private boolean canMakeProposal(AuditReplayCommand command, Predicate<String> authorized) {
        if (D6.equals(command.op())) return authorized.test("finance_d6_manage");
        if (D5.equals(command.op())) {
            var request = request(command, null, WithdrawalLimitsUpdateRequest.class);
            return !request.hasPhaseFields() && d5Authorization.canUpdateLimits(request, authorized);
        }
        var request = request(command, null, PayoutVndConfigUpdateRequest.class);
        return authorized.test("finance_d7_manage")
                && (!Boolean.TRUE.equals(request.forceInverted()) || authorized.test("finance_d7_force_inverted"));
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
        if (D6.equals(command.op())) {
            long makerId = ((Number) maker).longValue();
            if (!administrators.isActiveAdministrator(makerId)
                    || !permissions.getPermissionCodes(makerId).contains("finance_d6_manage")
                    || !permissions.getPermissionCodes(adminId).containsAll(
                            Set.of("finance_d6_manage", "platform_a2_operation_approve"))) {
                return ApiResult.fail(403, "D6_FX_QUOTE_APPROVAL_FORBIDDEN");
            }
        }
        if (executeChange && D7.equals(command.op()) && Boolean.TRUE.equals(
                request(command, null, PayoutVndConfigUpdateRequest.class).forceInverted())
                && !hasAuthority(authentication, "finance_d7_force_inverted")) {
            return ApiResult.fail(403, "D_WITHDRAWAL_CONFIG_APPROVAL_FORBIDDEN");
        }
        return ApiResult.ok();
    }

    public ApiResult<Map<String, Object>> preview(AuditReplayCommand command, String reason) {
        if (D6.equals(command.op())) return vietnam.previewFxQuote(request(command, reason, FxQuoteUpdateRequest.class));
        return D5.equals(command.op())
                ? finance.previewWithdrawalLimits(request(command, reason, WithdrawalLimitsUpdateRequest.class))
                : payoutVnd.previewUpdate(request(command, reason, PayoutVndConfigUpdateRequest.class));
    }

    public ApiResult<?> replay(AuditReplayCommand command, AuditReplayContext ctx) {
        if (!(command.params().get("proposalReason") instanceof String reason)) {
            return ApiResult.fail(422, "D_WITHDRAWAL_CONFIG_PROPOSAL_INVALID");
        }
        if (D6.equals(command.op())) return vietnam.updateFxQuote(ctx.idempotencyKey(),
                request(command, reason, FxQuoteUpdateRequest.class));
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
