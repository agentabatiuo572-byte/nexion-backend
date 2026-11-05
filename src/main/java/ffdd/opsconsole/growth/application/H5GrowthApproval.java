package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.growth.dto.GrowthEarnMilestoneUpdateRequest;
import ffdd.opsconsole.growth.dto.GrowthPowerUpUpdateRequest;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.domain.AuditLockTarget;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.platform.mapper.AuditObjectLockMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.SuperAdminAuthorization;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class H5GrowthApproval {
    public static final String MAKER_AUTHORITY = "growth_h5_write";
    private static final String POWER_UP = "h5_power_up_config";
    private static final String EARN = "h5_earn_milestone";
    private final OpsGrowthService growth;
    private final OpsGrowthCommandBoundary commands;
    private final SuperAdminAuthorization superAdmins;
    private final AuditObjectLockMapper lockMapper;

    public static boolean supports(AuditReplayCommand command) {
        return command != null && "H".equals(command.domain())
                && (POWER_UP.equals(command.op()) || EARN.equals(command.op()));
    }

    public boolean canPropose(AuditReplayCommand command) {
        return supports(command) && hasAuthority(SecurityContextHolder.getContext().getAuthentication(), MAKER_AUTHORITY);
    }

    public AuditReplayCommand bindMaker(AuditReplayCommand command, Long adminId) {
        if (adminId == null || adminId <= 0) throw new BizException(403, "H5_MAKER_REQUIRED");
        target(command);
        return new AuditReplayCommand("H", command.op(), Map.of("values", values(command), "makerAdminId", adminId));
    }

    public ApiResult<Void> validateChecker(AuditReplayCommand command, Long checkerId) {
        Object maker = command.params() == null ? null : command.params().get("makerAdminId");
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(maker instanceof Number) || checkerId == null || checkerId <= 0
                || !String.valueOf(checkerId).equals(authentication == null ? "" : authentication.getName())
                || !superAdmins.isSuperAdmin(authentication)
                || !hasAuthority(authentication, "platform_a2_operation_approve")) {
            return ApiResult.fail(403, "H5_APPROVAL_FORBIDDEN");
        }
        return String.valueOf(checkerId).equals(String.valueOf(maker))
                ? ApiResult.fail(403, "A2_MAKER_CHECKER_REQUIRED") : ApiResult.ok();
    }

    public static AuditLockTarget target(AuditReplayCommand command) {
        Map<?, ?> values = values(command);
        if (POWER_UP.equals(command.op())) {
            return new AuditLockTarget("H", "h5_power_up", String.valueOf(integer(values.get("powerUpId"))));
        }
        Object key = values.get("milestoneKey");
        if (!(key instanceof String text) || text.isBlank() || !text.equals(text.trim()) || text.length() > 64) {
            throw new BizException(422, "EARN_MILESTONE_KEY_INVALID");
        }
        return new AuditLockTarget("H", "h5_earn_milestone", text);
    }

    public ApiResult<OpsGrowthService.H5ConfigReview> preview(AuditReplayCommand command, String reason) {
        if (POWER_UP.equals(command.op())) {
            return growth.previewPowerUpApproval(integer(values(command).get("powerUpId")), powerUp(command, reason));
        }
        Map<?, ?> values = values(command);
        return growth.previewEarnMilestoneApproval(target(command).id(), earn(command, reason),
                decimal(values.get("expectedThresholdUsd")), decimal(values.get("expectedRewardNex")));
    }

    public ApiResult<?> replay(AuditReplayCommand command, AuditReplayContext context,
                               Long checkerId, String operationId) {
        ApiResult<Void> checker = validateChecker(command, checkerId);
        if (checker.getCode() != 0) return checker;
        if (!A2ReplayContext.isReplaying() || operationId == null || !operationId.equals(A2ReplayContext.operationId())) {
            return ApiResult.fail(403, "H5_APPROVAL_FORBIDDEN");
        }
        AuditLockTarget target = target(command);
        var locks = lockMapper.selectActiveByTicketId(operationId);
        if (locks.size() != 1 || !operationId.equals(locks.get(0).getTicketId()) || !target.equals(
                new AuditLockTarget(locks.get(0).getTargetDomain(), locks.get(0).getTargetType(), locks.get(0).getTargetId()))) {
            return ApiResult.fail(403, "H5_APPROVAL_FORBIDDEN");
        }
        if (!(command.params().get("proposalReason") instanceof String reason)) {
            return ApiResult.fail(422, "H5_PROPOSAL_INVALID");
        }
        if (POWER_UP.equals(command.op())) {
            var request = powerUp(command, reason);
            return commands.execute("H5", "POWER_UP_CONFIG_UPDATE", target.id(), context.idempotencyKey(), request,
                    () -> growth.executeApprovedPowerUp(context.idempotencyKey(), integer(values(command).get("powerUpId")),
                            request, target));
        }
        Map<?, ?> values = values(command);
        var request = earn(command, reason);
        return commands.execute("H5", "EARN_MILESTONE_UPDATE", target.id(), context.idempotencyKey(), request,
                () -> growth.executeApprovedEarnMilestone(context.idempotencyKey(), target.id(), request,
                        decimal(values.get("expectedThresholdUsd")), decimal(values.get("expectedRewardNex")), target));
    }

    private static Map<?, ?> values(AuditReplayCommand command) {
        if (!supports(command) || command.params() == null || !(command.params().get("values") instanceof Map<?, ?> values)
                || !values.keySet().equals(POWER_UP.equals(command.op())
                    ? Set.of("powerUpId", "day", "note", "expectedDay", "expectedNote")
                    : Set.of("milestoneKey", "thresholdUsd", "rewardNex", "expectedThresholdUsd", "expectedRewardNex"))) {
            throw new BizException(422, "H5_PROPOSAL_INVALID");
        }
        return values;
    }

    private static GrowthPowerUpUpdateRequest powerUp(AuditReplayCommand command, String reason) {
        Map<?, ?> values = values(command);
        if (!(values.get("note") instanceof String note) || !(values.get("expectedNote") instanceof String expectedNote)) {
            throw new BizException(422, "H5_PROPOSAL_INVALID");
        }
        return new GrowthPowerUpUpdateRequest(integer(values.get("day")), note,
                integer(values.get("expectedDay")), expectedNote, reason, null);
    }

    private static GrowthEarnMilestoneUpdateRequest earn(AuditReplayCommand command, String reason) {
        Map<?, ?> values = values(command);
        return new GrowthEarnMilestoneUpdateRequest(decimal(values.get("thresholdUsd")),
                decimal(values.get("rewardNex")), reason, null);
    }

    private static int integer(Object value) {
        if (!(value instanceof Integer || value instanceof Long || value instanceof BigInteger)
                || ((Number) value).longValue() <= 0 || new BigInteger(value.toString()).bitLength() > 31) {
            throw new BizException(422, "H5_PROPOSAL_INTEGER_INVALID");
        }
        return ((Number) value).intValue();
    }

    private static BigDecimal decimal(Object value) {
        if (!(value instanceof String text) || !text.matches("[0-9]+(?:\\.[0-9]{1,6})?")) {
            throw new BizException(422, "H5_PROPOSAL_AMOUNT_INVALID");
        }
        return new BigDecimal(text);
    }

    private static boolean hasAuthority(Authentication authentication, String authority) {
        return authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream().anyMatch(grant -> authority.equals(grant.getAuthority()));
    }
}
