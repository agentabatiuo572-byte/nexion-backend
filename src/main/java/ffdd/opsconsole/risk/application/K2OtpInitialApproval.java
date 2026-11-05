package ffdd.opsconsole.risk.application;

import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.domain.AuditLockTarget;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.platform.mapper.AuditObjectLockMapper;
import ffdd.opsconsole.risk.dto.RiskArbitrageParamUpdateRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.SuperAdminAuthorization;
import java.math.BigInteger;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Q-01: first effective OTP configuration is approved against both persistent sources. */
@Component
@RequiredArgsConstructor
public class K2OtpInitialApproval {
    public static final String MAKER_AUTHORITY = "risk_k2_write";
    public static final String OP = "k2_otp_initial_config";
    private final OpsRiskService risk;
    private final SuperAdminAuthorization superAdmins;
    private final AuditObjectLockMapper lockMapper;

    public static boolean supports(AuditReplayCommand command) {
        return command != null && "K".equals(command.domain()) && OP.equals(command.op());
    }

    public boolean canPropose(AuditReplayCommand command) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return supports(command) && hasAuthority(auth, MAKER_AUTHORITY)
                && (hasAuthority(auth, "platform_a2_proposal_create") || hasAuthority(auth, "platform_a2_write"));
    }

    public AuditReplayCommand bindMaker(AuditReplayCommand command, Long adminId) {
        if (!canPropose(command) || adminId == null || adminId <= 0) {
            throw new BizException(403, "K2_OTP_MAKER_FORBIDDEN");
        }
        var request = request(command, null);
        var review = risk.previewOtpInitialization(key(command), request);
        return new AuditReplayCommand("K", OP, Map.of("values", Map.of("key", key(command),
                "value", review.after(), "expectedVersion", request.expectedVersion()),
                "makerAdminId", adminId, "sourceState", review.sources()));
    }

    public static AuditLockTarget target(String key) {
        return new AuditLockTarget("K", "k2_otp_param", key);
    }

    public static AuditLockTarget target(AuditReplayCommand command) {
        return supports(command) ? target(key(command)) : null;
    }

    public ApiResult<OpsRiskService.K2OtpReview> preview(AuditReplayCommand command, String reason) {
        return ApiResult.ok(risk.previewOtpInitialization(key(command), request(command, reason)));
    }

    public ApiResult<Void> validateChecker(AuditReplayCommand command, Long checkerId) {
        Object maker = command.params() == null ? null : command.params().get("makerAdminId");
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(maker instanceof Number) || checkerId == null || checkerId <= 0
                || !String.valueOf(checkerId).equals(auth == null ? "" : auth.getName())
                || !superAdmins.isSuperAdmin(auth) || !hasAuthority(auth, "platform_a2_operation_approve")) {
            return ApiResult.fail(403, "K2_OTP_APPROVAL_FORBIDDEN");
        }
        return String.valueOf(checkerId).equals(String.valueOf(maker))
                ? ApiResult.fail(403, "A2_MAKER_CHECKER_REQUIRED") : ApiResult.ok();
    }

    public ApiResult<?> replay(AuditReplayCommand command, AuditReplayContext context,
                               Long checkerId, String operationId) {
        ApiResult<Void> checker = validateChecker(command, checkerId);
        if (checker.getCode() != 0) return checker;
        if (!A2ReplayContext.isReplaying() || operationId == null || !operationId.equals(A2ReplayContext.operationId())) {
            return ApiResult.fail(403, "K2_OTP_APPROVAL_FORBIDDEN");
        }
        var locks = lockMapper.selectActiveByTicketId(operationId);
        if (locks.size() != 1 || !target(command).equals(new AuditLockTarget(locks.get(0).getTargetDomain(),
                locks.get(0).getTargetType(), locks.get(0).getTargetId()))) {
            return ApiResult.fail(403, "K2_OTP_APPROVAL_FORBIDDEN");
        }
        if (!(command.params().get("proposalReason") instanceof String reason)
                || !(command.params().get("sourceState") instanceof Map<?, ?> sources)) {
            return ApiResult.fail(422, "K2_OTP_PROPOSAL_INVALID");
        }
        return risk.executeApprovedOtpInitialization(key(command), context.idempotencyKey(), request(command, reason),
                sources, ((Number) command.params().get("makerAdminId")).longValue());
    }

    private static String key(AuditReplayCommand command) {
        if (!supports(command) || command.params() == null || !(command.params().get("values") instanceof Map<?, ?> values)
                || !(values.get("key") instanceof String key)) throw new BizException(422, "K2_OTP_PROPOSAL_INVALID");
        return key;
    }

    private static RiskArbitrageParamUpdateRequest request(AuditReplayCommand command, String reason) {
        key(command);
        Map<?, ?> values = (Map<?, ?>) command.params().get("values");
        if (!values.keySet().equals(Set.of("key", "value", "expectedVersion"))
                || !(values.get("value") instanceof String value)) throw new BizException(422, "K2_OTP_PROPOSAL_INVALID");
        Object version = values.get("expectedVersion");
        if (!(version instanceof Integer || version instanceof Long || version instanceof BigInteger)
                || version instanceof BigInteger integer && integer.bitLength() > 63) {
            throw new BizException(422, "K2_PARAM_EXPECTED_VERSION_REQUIRED");
        }
        return new RiskArbitrageParamUpdateRequest(value, reason, null, ((Number) version).longValue());
    }

    private static boolean hasAuthority(Authentication auth, String authority) {
        return auth != null && auth.isAuthenticated()
                && auth.getAuthorities().stream().anyMatch(grant -> authority.equals(grant.getAuthority()));
    }
}
