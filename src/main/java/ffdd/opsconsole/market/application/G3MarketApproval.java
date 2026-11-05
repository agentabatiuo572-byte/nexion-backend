package ffdd.opsconsole.market.application;

import ffdd.opsconsole.market.dto.G3MarketPolicyChangeRequest;
import ffdd.opsconsole.platform.domain.AuditLockTarget;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.SuperAdminAuthorization;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class G3MarketApproval {
    public static final String OP = "g3_market_policy_change";
    static final AuditLockTarget CURVE_TARGET = new AuditLockTarget("G", "nex_market_curve", "weekly");
    static final AuditLockTarget PRICE_TARGET = new AuditLockTarget("G", "nex_market_override", "currentPrice");
    static final AuditLockTarget SCHEDULE_TARGET = new AuditLockTarget("G", "nex_market_control", "schedule");
    private final OpsNexMarketService market;
    private final G2G3AdminCommandService commands;
    private final SuperAdminAuthorization superAdmins;

    public record Review(AuditLockTarget target, String action, String before, String after,
                         Map<String, Object> beforeFields, Map<String, Object> afterFields,
                         String operationType, boolean amplifies) {}

    public static boolean supports(AuditReplayCommand command) {
        return command != null && "G".equals(command.domain()) && OP.equals(command.op());
    }

    public static String makerAuthority(AuditReplayCommand command) {
        if (!supports(command) || command.params() == null
                || !(command.params().get("values") instanceof Map<?, ?> values)) return null;
        return switch (String.valueOf(values.get("action"))) {
            case "curve-cell" -> switch (String.valueOf(values.get("field"))) {
                case "targetPrice" -> "finprod_g3_curve_target_price_write";
                case "pumpProbability" -> "finprod_g3_curve_pump_prob_write";
                default -> null;
            };
            case "current-price" -> "finprod_g3_override_price_write";
            case "initial-schedule" -> "finprod_g3_write";
            default -> null;
        };
    }

    public static Set<String> makerAuthorities(AuditReplayCommand command) {
        String authority = makerAuthority(command);
        return authority == null ? Set.of() : Set.of(authority);
    }

    public boolean canPropose(AuditReplayCommand command) {
        return hasAuthority(SecurityContextHolder.getContext().getAuthentication(), makerAuthority(command));
    }

    public AuditReplayCommand bindMaker(AuditReplayCommand command, Long adminId) {
        if (adminId == null || adminId <= 0) throw new BizException(403, "G3_POLICY_MAKER_REQUIRED");
        G3MarketPolicyChangeRequest request = request(command);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("action", request.action());
        if ("curve-cell".equals(request.action())) {
            values.put("dayIndex", request.dayIndex());
            values.put("field", request.field());
        }
        values.put("value", request.value());
        values.put("expectedRevision", request.expectedRevision());
        return new AuditReplayCommand("G", OP, Map.of("values", Map.copyOf(values), "makerAdminId", adminId));
    }

    public ApiResult<Void> validateChecker(AuditReplayCommand command, Long checkerId) {
        Object maker = command.params() == null ? null : command.params().get("makerAdminId");
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(maker instanceof Number) || checkerId == null || checkerId <= 0
                || !String.valueOf(checkerId).equals(authentication == null ? "" : authentication.getName())
                || !superAdmins.isSuperAdmin(authentication)
                || !hasAuthority(authentication, "platform_a2_operation_approve")) {
            return ApiResult.fail(403, "G3_POLICY_APPROVAL_FORBIDDEN");
        }
        return String.valueOf(checkerId).equals(String.valueOf(maker))
                ? ApiResult.fail(403, "A2_MAKER_CHECKER_REQUIRED") : ApiResult.ok();
    }

    public static AuditLockTarget target(AuditReplayCommand command) {
        if (!supports(command) || command.params() == null
                || !(command.params().get("values") instanceof Map<?, ?> values)) return null;
        return switch (String.valueOf(values.get("action"))) {
            case "curve-cell" -> CURVE_TARGET;
            case "current-price" -> PRICE_TARGET;
            case "initial-schedule" -> SCHEDULE_TARGET;
            default -> null;
        };
    }

    public ApiResult<Review> preview(AuditReplayCommand command, String reason) {
        return market.previewMarketPolicy(request(command), reason);
    }

    public ApiResult<?> replay(AuditReplayCommand command, AuditReplayContext context,
                               Long checkerId, String operationId) {
        ApiResult<Void> checker = validateChecker(command, checkerId);
        if (checker.getCode() != 0) return checker;
        if (!(command.params().get("proposalReason") instanceof String reason)) {
            return ApiResult.fail(422, "G3_POLICY_PROPOSAL_INVALID");
        }
        return commands.approvedMarketPolicy(context.idempotencyKey(), request(command), reason,
                context.operator(), operationId);
    }

    private G3MarketPolicyChangeRequest request(AuditReplayCommand command) {
        if (!supports(command) || command.params() == null
                || !(command.params().get("values") instanceof Map<?, ?> values)
                || !(values.get("action") instanceof String action)
                || !(values.get("value") instanceof String value) || value.isBlank() || value.length() > 512
                || !(values.get("expectedRevision") instanceof String revision) || !revision.matches("[0-9a-f]{64}")) {
            throw new BizException(422, "G3_POLICY_PROPOSAL_INVALID");
        }
        if ("curve-cell".equals(action)) {
            if (!values.keySet().equals(Set.of("action", "dayIndex", "field", "value", "expectedRevision"))
                    || !(values.get("dayIndex") instanceof Integer day) || day < 0 || day > 6
                    || !(values.get("field") instanceof String field)
                    || !Set.of("targetPrice", "pumpProbability").contains(field)) {
                throw new BizException(422, "G3_POLICY_PROPOSAL_INVALID");
            }
            return new G3MarketPolicyChangeRequest(action, day, field, value.trim(), revision);
        }
        if (!Set.of("current-price", "initial-schedule").contains(action)
                || !values.keySet().equals(Set.of("action", "value", "expectedRevision"))) {
            throw new BizException(422, "G3_POLICY_PROPOSAL_INVALID");
        }
        return new G3MarketPolicyChangeRequest(action, null, null, value.trim(), revision);
    }

    private static boolean hasAuthority(Authentication authentication, String authority) {
        return authority != null && authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream().anyMatch(grant -> authority.equals(grant.getAuthority()));
    }
}
