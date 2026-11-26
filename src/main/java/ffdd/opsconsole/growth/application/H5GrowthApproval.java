package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.growth.dto.GrowthEarnMilestoneCreateRequest;
import ffdd.opsconsole.growth.dto.GrowthEarnMilestoneUpdateRequest;
import ffdd.opsconsole.growth.dto.GrowthPowerUpUpdateRequest;
import ffdd.opsconsole.growth.dto.GrowthConfigUpdateRequest;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.domain.AuditLockTarget;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.platform.dto.AuditOperationProposalRequest;
import ffdd.opsconsole.platform.mapper.AuditObjectLockMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.SuperAdminAuthorization;
import java.math.BigDecimal;
import java.math.BigInteger;
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
public class H5GrowthApproval {
    public static final String MAKER_AUTHORITY = "growth_h5_write";
    private static final String POWER_UP = "h5_power_up_config";
    private static final String EARN = "h5_earn_milestone";
    private static final String EARN_CREATE = "h5_earn_milestone_create";
    static final String EARN_CREATE_ACTION = "新增收益里程碑";
    static final String EARN_CREATE_BEFORE = "不存在";
    private static final BigDecimal EARN_AMOUNT_MAX = new BigDecimal("100000000");
    private static final String STREAK = "h5_streak_milestone";
    private static final Set<String> POWER_UP_VALUES =
            Set.of("powerUpId", "day", "note", "expectedDay", "expectedNote");
    private static final Set<String> POWER_UP_DAY_VALUES =
            Set.of("powerUpId", "day", "expectedDay");
    private final OpsGrowthService growth;
    private final OpsGrowthCommandBoundary commands;
    private final SuperAdminAuthorization superAdmins;
    private final AuditObjectLockMapper lockMapper;

    public static boolean supports(AuditReplayCommand command) {
        return command != null && "H".equals(command.domain())
                && (POWER_UP.equals(command.op()) || EARN.equals(command.op()) || STREAK.equals(command.op())
                    || EARN_CREATE.equals(command.op()));
    }

    public static boolean isStreakMilestone(AuditReplayCommand command) {
        return command != null && "H".equals(command.domain()) && STREAK.equals(command.op());
    }

    public static boolean isEarnMilestoneCreate(AuditReplayCommand command) {
        return command != null && "H".equals(command.domain()) && EARN_CREATE.equals(command.op());
    }

    public static AuditOperationProposalRequest earnMilestoneCreateProposal(
            GrowthEarnMilestoneCreateRequest request, String operatorRole) {
        if (request == null) throw new BizException(422, "H5_PROPOSAL_INVALID");
        String key = request.milestoneKey();
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,64}")) {
            throw new BizException(422, "EARN_MILESTONE_KEY_INVALID");
        }
        String label = earnCreateLabel(request.label());
        int sortOrder = earnCreateSortOrder(request.sortOrder());
        var threshold = earnCreateAmount(request.thresholdUsd(), BigDecimal.ONE);
        var reward = earnCreateAmount(request.rewardNex(), BigDecimal.ZERO);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("milestoneKey", key);
        values.put("label", label);
        values.put("sortOrder", sortOrder);
        values.put("thresholdUsd", threshold.toPlainString());
        values.put("rewardNex", reward.toPlainString());
        var command = new AuditReplayCommand("H", EARN_CREATE, Map.of("values", values));
        return new AuditOperationProposalRequest(EARN_CREATE_ACTION, key, EARN_CREATE_BEFORE, null,
                null, operatorRole, "fund", true, false, null, request.reason(), "H5", command, target(command), List.of());
    }

    public boolean canPropose(AuditReplayCommand command) {
        return supports(command) && hasAuthority(SecurityContextHolder.getContext().getAuthentication(), MAKER_AUTHORITY);
    }

    public AuditReplayCommand bindMaker(AuditReplayCommand command, Long adminId) {
        if (adminId == null || adminId <= 0) throw new BizException(403, "H5_MAKER_REQUIRED");
        target(command);
        if (STREAK.equals(command.op())) {
            if (!canPropose(command)) throw new BizException(403, "H5_MAKER_FORBIDDEN");
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("values", values(command));
            params.put("makerAdminId", adminId);
            params.put("before", growth.streakMilestoneApprovalBefore(
                    integer(values(command).get("milestoneId")), streak(command, null).expectedValue()));
            return new AuditReplayCommand("H", STREAK, params);
        }
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
        if (STREAK.equals(command.op())) {
            return new AuditLockTarget("H", "h5_streak_milestone", String.valueOf(integer(values.get("milestoneId"))));
        }
        Object key = values.get("milestoneKey");
        if (!(key instanceof String text) || text.isBlank() || !text.equals(text.trim()) || text.length() > 64) {
            throw new BizException(422, "EARN_MILESTONE_KEY_INVALID");
        }
        if (isEarnMilestoneCreate(command) && !text.matches("[A-Za-z0-9._:-]{1,64}")) {
            throw new BizException(422, "EARN_MILESTONE_KEY_INVALID");
        }
        return new AuditLockTarget("H", "h5_earn_milestone", text);
    }

    public ApiResult<OpsGrowthService.H5ConfigReview> preview(AuditReplayCommand command, String reason) {
        if (POWER_UP.equals(command.op())) {
            return growth.previewPowerUpApproval(integer(values(command).get("powerUpId")), powerUp(command, reason));
        }
        if (STREAK.equals(command.op())) {
            return growth.previewStreakMilestoneApproval(integer(values(command).get("milestoneId")), streak(command, reason));
        }
        if (isEarnMilestoneCreate(command)) {
            return growth.previewEarnMilestoneCreateApproval(earnCreate(command, reason), target(command));
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
        if (STREAK.equals(command.op())) {
            if (!(command.params().get("before") instanceof Map<?, ?> before)) {
                return ApiResult.fail(422, "H5_PROPOSAL_INVALID");
            }
            var request = streak(command, reason);
            return commands.execute("H5", "STREAK_MILESTONE_UPDATE", target.id(), context.idempotencyKey(), request,
                    () -> growth.executeApprovedStreakMilestone(context.idempotencyKey(),
                            integer(values(command).get("milestoneId")), request, before, target));
        }
        if (isEarnMilestoneCreate(command)) {
            var request = earnCreate(command, reason);
            return commands.execute("H5", "EARN_MILESTONE_CREATE", target.id(), context.idempotencyKey(), request,
                    () -> growth.executeApprovedEarnMilestoneCreate(context.idempotencyKey(), request, target));
        }
        Map<?, ?> values = values(command);
        var request = earn(command, reason);
        return commands.execute("H5", "EARN_MILESTONE_UPDATE", target.id(), context.idempotencyKey(), request,
                () -> growth.executeApprovedEarnMilestone(context.idempotencyKey(), target.id(), request,
                        decimal(values.get("expectedThresholdUsd")), decimal(values.get("expectedRewardNex")), target));
    }

    private static Map<?, ?> values(AuditReplayCommand command) {
        if (!supports(command) || command.params() == null
                || !(command.params().get("values") instanceof Map<?, ?> values)) {
            throw new BizException(422, "H5_PROPOSAL_INVALID");
        }
        Set<?> expected = POWER_UP.equals(command.op())
                ? (values.keySet().equals(POWER_UP_DAY_VALUES) ? POWER_UP_DAY_VALUES : POWER_UP_VALUES)
                : STREAK.equals(command.op()) ? Set.of("milestoneId", "reward", "expectedReward")
                : EARN_CREATE.equals(command.op()) ? Set.of("milestoneKey", "label", "sortOrder", "thresholdUsd", "rewardNex")
                : Set.of("milestoneKey", "thresholdUsd", "rewardNex", "expectedThresholdUsd", "expectedRewardNex");
        if (!values.keySet().equals(expected)) throw new BizException(422, "H5_PROPOSAL_INVALID");
        return values;
    }

    private static GrowthPowerUpUpdateRequest powerUp(AuditReplayCommand command, String reason) {
        Map<?, ?> values = values(command);
        Object note = values.get("note");
        Object expectedNote = values.get("expectedNote");
        if ((note != null && !(note instanceof String))
                || (expectedNote != null && !(expectedNote instanceof String))) {
            throw new BizException(422, "H5_PROPOSAL_INVALID");
        }
        return new GrowthPowerUpUpdateRequest(integer(values.get("day")), (String) note,
                integer(values.get("expectedDay")), (String) expectedNote, reason, null);
    }

    private static GrowthEarnMilestoneUpdateRequest earn(AuditReplayCommand command, String reason) {
        Map<?, ?> values = values(command);
        return new GrowthEarnMilestoneUpdateRequest(decimal(values.get("thresholdUsd")),
                decimal(values.get("rewardNex")), reason, null);
    }

    private static GrowthEarnMilestoneCreateRequest earnCreate(AuditReplayCommand command, String reason) {
        var fields = values(command);
        if (!(fields.get("label") instanceof String label)
                || !(fields.get("sortOrder") instanceof Integer sortOrder) || sortOrder < 0) {
            throw new BizException(422, "H5_PROPOSAL_INVALID");
        }
        return new GrowthEarnMilestoneCreateRequest((String) fields.get("milestoneKey"), earnCreateLabel(label),
                earnCreateAmount(decimal(fields.get("thresholdUsd")), BigDecimal.ONE),
                earnCreateAmount(decimal(fields.get("rewardNex")), BigDecimal.ZERO), BigDecimal.valueOf(sortOrder), reason);
    }

    private static String earnCreateLabel(String label) {
        String normalized = label == null ? "" : label.strip();
        if (normalized.isBlank() || normalized.codePointCount(0, normalized.length()) > 128) {
            throw new BizException(422, "EARN_MILESTONE_LABEL_INVALID");
        }
        return normalized;
    }

    private static int earnCreateSortOrder(BigDecimal sortOrder) {
        if (sortOrder == null || sortOrder.signum() < 0 || sortOrder.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0
                || sortOrder.stripTrailingZeros().scale() > 0) {
            throw new BizException(422, "EARN_MILESTONE_SORT_ORDER_INVALID");
        }
        return sortOrder.intValueExact();
    }

    private static BigDecimal earnCreateAmount(BigDecimal amount, BigDecimal min) {
        if (amount == null) throw new BizException(422, "EARN_MILESTONE_AMOUNTS_REQUIRED");
        BigDecimal normalized = amount.stripTrailingZeros();
        if (normalized.scale() > 6) throw new BizException(422, "EARN_MILESTONE_AMOUNT_PRECISION_INVALID");
        if (normalized.compareTo(min) < 0 || normalized.compareTo(EARN_AMOUNT_MAX) > 0) {
            throw new BizException(422, "EARN_MILESTONE_AMOUNT_OUT_OF_RANGE");
        }
        return normalized;
    }

    private static GrowthConfigUpdateRequest streak(AuditReplayCommand command, String reason) {
        Map<?, ?> values = values(command);
        if (!(values.get("reward") instanceof String reward) || !(values.get("expectedReward") instanceof String expected)) {
            throw new BizException(422, "H5_PROPOSAL_INVALID");
        }
        return new GrowthConfigUpdateRequest(null, reward, reason, null, expected);
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
