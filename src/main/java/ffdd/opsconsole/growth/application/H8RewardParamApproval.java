package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.growth.dto.ReferralRewardParamUpdateRequest;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.domain.AuditLockTarget;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.platform.mapper.AuditObjectLockMapper;
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

@Component
@RequiredArgsConstructor
public class H8RewardParamApproval {
    public static final String MAKER_AUTHORITY = "growth_h8_write";
    public static final String OP = "h8_reward_param_change";
    static final AuditLockTarget TARGET = new AuditLockTarget("H", "h8_reward_policy", "current");
    private final OpsReferralRewardService rewards;
    private final SuperAdminAuthorization superAdmins;
    private final AuditObjectLockMapper lockMapper;

    public static boolean supports(AuditReplayCommand command) {
        return command != null && "H".equals(command.domain()) && OP.equals(command.op());
    }

    public boolean canPropose(AuditReplayCommand command) {
        return supports(command) && hasAuthority(SecurityContextHolder.getContext().getAuthentication(), MAKER_AUTHORITY);
    }

    public AuditReplayCommand bindMaker(AuditReplayCommand command, Long adminId) {
        if (adminId == null || adminId <= 0) throw new BizException(403, "H8_PARAM_MAKER_REQUIRED");
        var request = request(command, null);
        return new AuditReplayCommand("H", OP, Map.of("values", Map.of(
                "key", request.key(), "value", request.value(), "expectedVersion", request.expectedVersion()),
                "makerAdminId", adminId));
    }

    public ApiResult<Void> validateChecker(AuditReplayCommand command, Long checkerId) {
        Object maker = command.params() == null ? null : command.params().get("makerAdminId");
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(maker instanceof Number) || checkerId == null || checkerId <= 0
                || !String.valueOf(checkerId).equals(authentication == null ? "" : authentication.getName())
                || !superAdmins.isSuperAdmin(authentication)
                || !hasAuthority(authentication, "platform_a2_operation_approve")) {
            return ApiResult.fail(403, "H8_PARAM_APPROVAL_FORBIDDEN");
        }
        return String.valueOf(checkerId).equals(String.valueOf(maker))
                ? ApiResult.fail(403, "A2_MAKER_CHECKER_REQUIRED") : ApiResult.ok();
    }

    public static AuditLockTarget target(AuditReplayCommand command) {
        return supports(command) ? TARGET : null;
    }

    public ApiResult<OpsReferralRewardService.H8ParamReview> preview(AuditReplayCommand command, String reason) {
        return ApiResult.ok(rewards.previewParamApproval(request(command, reason)));
    }

    public ApiResult<?> replay(AuditReplayCommand command, AuditReplayContext context,
                               Long checkerId, String operationId) {
        ApiResult<Void> checker = validateChecker(command, checkerId);
        if (checker.getCode() != 0) return checker;
        if (!A2ReplayContext.isReplaying() || operationId == null || !operationId.equals(A2ReplayContext.operationId())) {
            return ApiResult.fail(403, "H8_PARAM_APPROVAL_FORBIDDEN");
        }
        var locks = lockMapper.selectActiveByTicketId(operationId);
        if (locks.size() != 1 || !operationId.equals(locks.get(0).getTicketId()) || !TARGET.equals(
                new AuditLockTarget(locks.get(0).getTargetDomain(), locks.get(0).getTargetType(), locks.get(0).getTargetId()))) {
            return ApiResult.fail(403, "H8_PARAM_APPROVAL_FORBIDDEN");
        }
        if (!(command.params().get("proposalReason") instanceof String reason)) {
            return ApiResult.fail(422, "H8_PARAM_PROPOSAL_INVALID");
        }
        return ApiResult.ok(rewards.executeApprovedParam(context.idempotencyKey(), request(command, reason), TARGET));
    }

    private ReferralRewardParamUpdateRequest request(AuditReplayCommand command, String reason) {
        if (!supports(command) || command.params() == null
                || !(command.params().get("values") instanceof Map<?, ?> values)
                || !values.keySet().equals(Set.of("key", "value", "expectedVersion"))
                || !(values.get("key") instanceof String key) || !(values.get("value") instanceof String value)) {
            throw new BizException(422, "H8_PARAM_PROPOSAL_INVALID");
        }
        Object version = values.get("expectedVersion");
        if (!(version instanceof Integer || version instanceof Long || version instanceof BigInteger)
                || version instanceof BigInteger integer && integer.bitLength() > 63) {
            throw new BizException(422, "H8_EXPECTED_VERSION_REQUIRED");
        }
        return new ReferralRewardParamUpdateRequest(key, value, ((Number) version).longValue(), reason, null);
    }

    private static boolean hasAuthority(Authentication authentication, String authority) {
        return authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream().anyMatch(grant -> authority.equals(grant.getAuthority()));
    }
}
