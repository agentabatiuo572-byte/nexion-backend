package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.growth.dto.GrowthConfigUpdateRequest;
import ffdd.opsconsole.growth.dto.GrowthMissionRequest;
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
public class H3GrowthApproval {
    public static final String MAKER_AUTHORITY = "growth_h3_write";
    private static final String CONFIG = "h3_quest_config";
    private static final String CREATE = "h3_mission_create";
    private final OpsGrowthService growth;
    private final OpsGrowthCommandBoundary commands;
    private final SuperAdminAuthorization superAdmins;
    private final AuditObjectLockMapper lockMapper;

    public static boolean supports(AuditReplayCommand command) {
        return command != null && "H".equals(command.domain())
                && (CONFIG.equals(command.op()) || CREATE.equals(command.op()));
    }

    public boolean canPropose(AuditReplayCommand command) {
        return supports(command) && hasAuthority(SecurityContextHolder.getContext().getAuthentication(), MAKER_AUTHORITY);
    }

    public AuditReplayCommand bindMaker(AuditReplayCommand command, Long adminId) {
        if (adminId == null || adminId <= 0) throw new BizException(403, "H3_MAKER_REQUIRED");
        AuditLockTarget target = target(command);
        return new AuditReplayCommand("H", command.op(), Map.of("values", values(command),
                "makerAdminId", adminId, "expectedState", growth.h3ApprovalState(target)));
    }

    public ApiResult<Void> validateChecker(AuditReplayCommand command, Long checkerId) {
        Object maker = command.params() == null ? null : command.params().get("makerAdminId");
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(maker instanceof Number) || checkerId == null || checkerId <= 0
                || !String.valueOf(checkerId).equals(authentication == null ? "" : authentication.getName())
                || !superAdmins.isSuperAdmin(authentication)
                || !hasAuthority(authentication, "platform_a2_operation_approve")) {
            return ApiResult.fail(403, "H3_APPROVAL_FORBIDDEN");
        }
        return String.valueOf(checkerId).equals(String.valueOf(maker))
                ? ApiResult.fail(403, "A2_MAKER_CHECKER_REQUIRED") : ApiResult.ok();
    }

    public static AuditLockTarget target(AuditReplayCommand command) {
        Map<?, ?> values = values(command);
        if (CREATE.equals(command.op())) {
            String code = text(values, "missionCode");
            if (!code.matches("[A-Za-z0-9_-]{2,64}")) throw new BizException(422, "MISSION_CODE_INVALID");
            return new AuditLockTarget("H", "h3_mission", code);
        }
        String key = text(values, "configKey");
        if (!(key.equals("dayOne.triReward") || key.matches("weekly\\.mult\\.P[1-6]")
                || key.matches("(?:mission|monthly)\\.[A-Za-z0-9_-]{2,64}\\.reward")
                || key.matches("promoBanner\\.(?:baseReward|multiplier|status)"))) {
            throw new BizException(422, "H3_APPROVAL_CONFIG_INVALID");
        }
        return new AuditLockTarget("H", "h3_quest_config", key);
    }

    public ApiResult<Map<String, Object>> preview(AuditReplayCommand command, String reason) {
        AuditLockTarget target = target(command);
        return CREATE.equals(command.op()) ? growth.previewH3Mission(mission(command, reason))
                : growth.previewH3Config(target.id(), config(command, reason));
    }

    public ApiResult<?> replay(AuditReplayCommand command, AuditReplayContext context,
                               Long checkerId, String operationId) {
        ApiResult<Void> checker = validateChecker(command, checkerId);
        if (checker.getCode() != 0) return checker;
        if (!A2ReplayContext.isReplaying() || operationId == null || !operationId.equals(A2ReplayContext.operationId())) {
            return ApiResult.fail(403, "H3_APPROVAL_FORBIDDEN");
        }
        AuditLockTarget target = target(command);
        var locks = lockMapper.selectActiveByTicketId(operationId);
        if (locks.size() != 1 || !operationId.equals(locks.get(0).getTicketId()) || !target.equals(
                new AuditLockTarget(locks.get(0).getTargetDomain(), locks.get(0).getTargetType(), locks.get(0).getTargetId()))) {
            return ApiResult.fail(403, "H3_APPROVAL_FORBIDDEN");
        }
        if (!growth.h3ApprovalState(target).equals(command.params().get("expectedState"))) {
            return ApiResult.fail(409, "H3_CONFIG_STALE");
        }
        if (!(command.params().get("proposalReason") instanceof String reason)) {
            return ApiResult.fail(422, "H3_PROPOSAL_INVALID");
        }
        if (CREATE.equals(command.op())) {
            var request = mission(command, reason);
            return commands.execute("H3", "MISSION_CREATE", target.id(), context.idempotencyKey(), request,
                    () -> growth.executeApprovedH3Mission(context.idempotencyKey(), request, target));
        }
        var request = config(command, reason);
        return commands.execute("H3", "QUEST_CONFIG_UPDATE", target.id(), context.idempotencyKey(), request,
                () -> growth.executeApprovedH3Config(context.idempotencyKey(), target.id(), request, target));
    }

    private static Map<?, ?> values(AuditReplayCommand command) {
        if (!supports(command) || command.params() == null || !(command.params().get("values") instanceof Map<?, ?> values)
                || !values.keySet().equals(CONFIG.equals(command.op()) ? Set.of("configKey", "value", "expectedValue")
                    : Set.of("missionCode", "missionName", "missionType", "rewardPoints", "category", "actionRoute"))) {
            throw new BizException(422, "H3_PROPOSAL_INVALID");
        }
        return values;
    }

    private static GrowthConfigUpdateRequest config(AuditReplayCommand command, String reason) {
        Map<?, ?> values = values(command);
        return new GrowthConfigUpdateRequest(null, text(values, "value"), reason, null, text(values, "expectedValue"));
    }

    private static GrowthMissionRequest mission(AuditReplayCommand command, String reason) {
        Map<?, ?> values = values(command);
        Object reward = values.get("rewardPoints");
        if (!(reward instanceof Integer || reward instanceof Long || reward instanceof BigInteger)
                || ((Number) reward).longValue() < 0 || new BigInteger(reward.toString()).bitLength() > 31) {
            throw new BizException(422, "MISSION_REWARD_INVALID");
        }
        return new GrowthMissionRequest(text(values, "missionCode"), text(values, "missionName"),
                text(values, "missionType"), ((Number) reward).intValue(), text(values, "category"),
                text(values, "actionRoute"), reason, null);
    }

    private static String text(Map<?, ?> values, String key) {
        if (!(values.get(key) instanceof String text) || text.isBlank() || !text.equals(text.trim())) {
            throw new BizException(422, "H3_PROPOSAL_INVALID");
        }
        return text;
    }

    private static boolean hasAuthority(Authentication authentication, String authority) {
        return authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream().anyMatch(grant -> authority.equals(grant.getAuthority()));
    }
}
