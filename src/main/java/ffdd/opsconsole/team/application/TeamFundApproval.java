package ffdd.opsconsole.team.application;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ffdd.opsconsole.platform.domain.AuditLockTarget;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.SuperAdminAuthorization;
import ffdd.opsconsole.team.dto.AmbassadorPolicyUpdateRequest;
import ffdd.opsconsole.team.dto.BinarySettlementRequest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TeamFundApproval {
    private static final String SETTLEMENT_OP = "f3_binary_settle";
    private static final String AMBASSADOR_OP = "f4_ambassador_policy_change";
    private final ObjectMapper objectMapper;
    private final SuperAdminAuthorization superAdmins;
    private final BinaryCommissionSettlementService settlements;
    private final OpsTeamService teams;

    public static boolean supports(AuditReplayCommand command) {
        return command != null && "F".equals(command.domain())
                && (SETTLEMENT_OP.equals(command.op()) || AMBASSADOR_OP.equals(command.op()));
    }

    public static boolean isSettlement(AuditReplayCommand command) {
        return supports(command) && SETTLEMENT_OP.equals(command.op());
    }

    public static String makerAuthority(AuditReplayCommand command) {
        return isSettlement(command) ? "network_f3_write" : "network_f4_pool_fund";
    }

    public static String source(AuditReplayCommand command) {
        return isSettlement(command) ? "F3" : "F4";
    }

    public boolean canPropose(AuditReplayCommand command) {
        return supports(command) && hasAuthority(SecurityContextHolder.getContext().getAuthentication(), makerAuthority(command));
    }

    public AuditReplayCommand bindMaker(AuditReplayCommand command, Long adminId) {
        if (adminId == null) throw new BizException(403, "FUND_APPROVAL_MAKER_REQUIRED");
        return new AuditReplayCommand(command.domain(), command.op(),
                Map.of("values", values(command), "makerAdminId", adminId));
    }

    public ApiResult<Void> validateChecker(AuditReplayCommand command, Long adminId) {
        Object maker = command.params() == null ? null : command.params().get("makerAdminId");
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(maker instanceof Number) || adminId == null || !superAdmins.isSuperAdmin(authentication)
                || !hasAuthority(authentication, "platform_a2_operation_approve")) {
            return ApiResult.fail(403, "FUND_APPROVAL_CHECKER_FORBIDDEN");
        }
        return String.valueOf(adminId).equals(String.valueOf(maker))
                ? ApiResult.fail(403, "A2_MAKER_CHECKER_REQUIRED") : ApiResult.ok();
    }

    public record Review(
            AuditLockTarget target, String action, String before, String after,
            Map<String, Object> beforeFields, Map<String, Object> afterFields) { }

    public ApiResult<Review> preview(AuditReplayCommand command, String reason) {
        if (isSettlement(command)) {
            var request = settlementRequest(command, reason);
            settlements.validateManualProposal(request.ownerUserId(), request.settlementDate());
            var target = new AuditLockTarget("F", "binary_settlement", request.ownerUserId() + ":" + request.settlementDate());
            return ApiResult.ok(new Review(target, "手动结算团队平衡奖励", "按当前订单和结算规则核对",
                    "批准时核算结算金额和兑付覆盖率", null, null));
        }
        var preview = teams.previewAmbassadorPolicyApproval(ambassadorRequest(command, reason));
        if (preview.getCode() != 0) return ApiResult.fail(preview.getCode(), preview.getMessage());
        var review = preview.getData();
        return ApiResult.ok(new Review(review.target(), "提高区域大使申请预算", review.before(), review.after(),
                review.beforeFields(), review.afterFields()));
    }

    public static AuditLockTarget target(AuditReplayCommand command) {
        if (command.params() == null || !(command.params().get("targetId") instanceof String id) || id.isBlank()) {
            return null;
        }
        return new AuditLockTarget("F", isSettlement(command) ? "binary_settlement" : "ambassador_policy", id);
    }

    public ApiResult<?> replay(AuditReplayCommand command, AuditReplayContext context, Long checkerId, String operationId) {
        AuditLockTarget target = target(command);
        if (target == null || !(command.params().get("proposalReason") instanceof String reason)) {
            return ApiResult.fail(422, "FUND_APPROVAL_PROPOSAL_INVALID");
        }
        if (isSettlement(command)) {
            var request = settlementRequest(command, reason);
            if (!target.id().equals(request.ownerUserId() + ":" + request.settlementDate())) {
                return ApiResult.fail(403, "A2_BUSINESS_CONTEXT_MISMATCH");
            }
            var result = settlements.settleApproved(request.ownerUserId(), request.settlementDate(), reason,
                    checkerId, context.operator(), operationId, context.idempotencyKey());
            if ("BLOCKED".equals(result.status())) {
                throw new BizException(409, result.reason());
            }
            return ApiResult.ok(result);
        }
        return teams.executeApprovedAmbassadorPolicy(context.idempotencyKey(),
                ambassadorRequest(command, reason));
    }

    private BinarySettlementRequest settlementRequest(AuditReplayCommand command, String reason) {
        Map<?, ?> values = values(command);
        Object owner = values.get("ownerUserId");
        if (!(owner instanceof Integer || owner instanceof Long)
                || ((Number) owner).longValue() <= 0 || ((Number) owner).longValue() > 9_007_199_254_740_991L
                || !(values.get("settlementDate") instanceof String date) || !date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            throw new BizException(422, "F3_SETTLEMENT_PROPOSAL_INVALID");
        }
        return request(command, reason, BinarySettlementRequest.class);
    }

    private AmbassadorPolicyUpdateRequest ambassadorRequest(AuditReplayCommand command, String reason) {
        Map<?, ?> values = values(command);
        requireExactBudget(values.get("defaultBudgetUsdt"));
        if (values.get("buckets") instanceof List<?> buckets) {
            for (Object item : buckets) {
                if (item instanceof Map<?, ?> bucket) {
                    requireExactBudget(bucket.get("minBudgetUsdt"));
                    requireExactBudget(bucket.get("maxBudgetUsdt"));
                }
            }
        }
        return request(command, reason, AmbassadorPolicyUpdateRequest.class);
    }

    private static void requireExactBudget(Object value) {
        if (value instanceof Double || value instanceof Float) {
            throw new BizException(422, "AMBASSADOR_BUDGET_DECIMAL_STRING_REQUIRED");
        }
    }

    private static Map<?, ?> values(AuditReplayCommand command) {
        if (command.params() == null || !(command.params().get("values") instanceof Map<?, ?> values)
                || values.isEmpty() || values.containsKey("reason") || values.containsKey("operator")) {
            throw new BizException(422, "FUND_APPROVAL_PROPOSAL_INVALID");
        }
        return values;
    }

    private <T> T request(AuditReplayCommand command, String reason, Class<T> type) {
        ObjectNode payload = objectMapper.valueToTree(values(command));
        payload.put("reason", reason);
        try {
            return objectMapper.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT).readValue(payload);
        } catch (IOException invalid) {
            throw new BizException(422, "FUND_APPROVAL_PROPOSAL_INVALID");
        }
    }

    private static boolean hasAuthority(Authentication authentication, String authority) {
        return authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream().anyMatch(grant -> authority.equals(grant.getAuthority()));
    }
}
