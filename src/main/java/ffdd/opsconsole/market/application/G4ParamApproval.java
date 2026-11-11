package ffdd.opsconsole.market.application;

import ffdd.opsconsole.market.dto.NexMarketValueUpdateRequest;
import ffdd.opsconsole.market.facade.GenesisParamPermissions;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.domain.AuditLockTarget;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.platform.mapper.AuditObjectLockMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.SuperAdminAuthorization;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * BUG-286: G4 创世参数中放大资金流出的方向必须由另一位超管批准后回放执行。
 *
 * <p>提案时绑定参数键、拟值、当前值快照和现行系列；提案与批准各按 T11 复核覆盖率。
 * 系列或当前值在待审期间变化时，批准作废，不会把一次批准应用到另一条系列或另一个旧值上。</p>
 */
@Component
@RequiredArgsConstructor
public class G4ParamApproval {
    public static final String OP = "g4_genesis_param";
    static final Set<String> AMPLIFY_KEYS = Set.of(
            "supply", "price", "royalty", "divBase", "emissionCurve", "airdropPct", "airdropLockDays");
    private static final String MAKER_FIELD = "makerAdminId";
    private static final String REASON_FIELD = "proposalReason";

    private final OpsNexMarketService market;
    private final G4AdminCommandService commands;
    private final SuperAdminAuthorization superAdmins;
    private final AuditObjectLockMapper locks;

    /** Dividend keeps its own economic contract and is handled by G4DividendApproval. */
    public static boolean supports(AuditReplayCommand command) {
        if (command == null || !"G".equals(command.domain()) || !OP.equals(command.op()) || command.params() == null)
            return false;
        String paramKey = text(command.params().get("paramKey"));
        return AMPLIFY_KEYS.contains(paramKey);
    }

    public static String makerAuthority(AuditReplayCommand command) {
        return supports(command) ? GenesisParamPermissions.forKey(text(command.params().get("paramKey"))) : null;
    }

    public static AuditLockTarget target(AuditReplayCommand command) {
        return supports(command) ? new AuditLockTarget("G", "genesis_param", text(command.params().get("paramKey"))) : null;
    }

    public boolean canPropose(AuditReplayCommand command) {
        return supports(command) && (hasAuthority(GenesisParamPermissions.forKey(
                text(command.params().get("paramKey")))));
    }

    /** Server binds the old value and the active series; the browser cannot choose either. */
    public AuditReplayCommand bindMaker(AuditReplayCommand command, Long maker) {
        if (maker == null || maker <= 0) throw new BizException(403, "G4_PARAM_MAKER_REQUIRED");
        if (!supports(command)) throw new BizException(422, "G4_PARAM_PROPOSAL_INVALID");
        String paramKey = text(command.params().get("paramKey"));
        String current = market.genesisParamCurrentValue(paramKey);
        String series = market.genesisParamSeriesKey();
        String candidate = market.genesisParamCandidateValue(paramKey, text(command.params().get("value")));
        if (current == null || series == null) throw new BizException(422, "G4_PARAM_KEY_INVALID");
        if (candidate == null) throw new BizException(422, "G4_PARAM_VALUE_INVALID");
        if (!market.genesisParamAmplifies(paramKey, current, candidate))
            throw new BizException(409, "G4_PARAM_APPROVAL_NOT_REQUIRED");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("paramKey", paramKey);
        params.put("value", candidate);
        params.put("expectedValue", current);
        params.put("expectedSeries", series);
        if (decisionRequired(paramKey)) {
            String decisionRef = text(command.params().get("decisionRef"));
            if (decisionRef.isBlank()) throw new BizException(422, "G4_DECISION_REF_REQUIRED");
            params.put("decisionRef", decisionRef);
        }
        params.put(MAKER_FIELD, maker);
        return new AuditReplayCommand("G", OP, Map.copyOf(params));
    }

    public ApiResult<Void> validateChecker(AuditReplayCommand command, Long checker) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object maker = command == null || command.params() == null ? null : command.params().get(MAKER_FIELD);
        String authority = makerAuthority(command);
        if (!(maker instanceof Number) || checker == null || checker <= 0 || authority == null
                || !String.valueOf(checker).equals(authentication == null ? "" : authentication.getName())
                || !superAdmins.isSuperAdmin(authentication) || !hasAuthority(authority)
                || !hasAuthority("platform_a2_operation_approve")) {
            return ApiResult.fail(403, "G4_PARAM_APPROVAL_FORBIDDEN");
        }
        return String.valueOf(checker).equals(String.valueOf(maker))
                ? ApiResult.fail(403, "A2_MAKER_CHECKER_REQUIRED") : ApiResult.ok();
    }

    /** Proposal-time review: old value and series still current, real amplifying direction, coverage by candidate Δ. */
    public Map<String, Object> preview(AuditReplayCommand command, String reason) {
        Map<String, Object> params = values(command);
        requireText(reason, 8, 200, "REASON_LENGTH_INVALID");
        String paramKey = text(params.get("paramKey"));
        String series = market.genesisParamSeriesKey();
        if (!text(params.get("expectedSeries")).equals(series))
            throw new BizException(409, "G4_GENESIS_PARAM_STALE");
        String current = market.genesisParamCurrentValue(paramKey);
        if (current == null || !text(params.get("expectedValue")).equals(current))
            throw new BizException(409, "G4_GENESIS_PARAM_STALE");
        String candidate = market.genesisParamCandidateValue(paramKey, text(params.get("value")));
        if (candidate == null) throw new BizException(422, "G4_PARAM_VALUE_INVALID");
        if (!market.genesisParamAmplifies(paramKey, current, candidate))
            throw new BizException(409, "G4_PARAM_APPROVAL_NOT_REQUIRED");
        BigDecimal delta = market.genesisParamLiabilityIncrease(paramKey, candidate);
        market.requireCoverageRedline(delta);
        Map<String, Object> review = new LinkedHashMap<>();
        review.put("before", series + " · " + paramKey + " = " + current);
        review.put("after", series + " · " + paramKey + " = " + candidate
                + " · 确定新增应付 Δ " + delta.toPlainString() + " USDT");
        return review;
    }

    public ApiResult<?> replay(AuditReplayCommand command, AuditReplayContext context, Long checker,
                               String operationId) {
        ApiResult<Void> checkerResult = validateChecker(command, checker);
        if (checkerResult.getCode() != 0) return checkerResult;
        if (!A2ReplayContext.isReplaying() || operationId == null
                || !operationId.equals(A2ReplayContext.operationId()))
            return ApiResult.fail(403, "G4_PARAM_APPROVAL_FORBIDDEN");
        AuditLockTarget expected = target(command);
        var owned = locks.selectActiveByTicketId(operationId);
        if (expected == null || owned == null || owned.size() != 1
                || !operationId.equals(owned.get(0).getTicketId())
                || !expected.equals(new AuditLockTarget(owned.get(0).getTargetDomain(),
                        owned.get(0).getTargetType(), owned.get(0).getTargetId())))
            return ApiResult.fail(403, "G4_PARAM_APPROVAL_FORBIDDEN");
        Map<String, Object> params = values(command);
        String paramKey = text(params.get("paramKey"));
        String series = market.genesisParamSeriesKey();
        String current = market.genesisParamCurrentValue(paramKey);
        String candidate = market.genesisParamCandidateValue(paramKey, text(params.get("value")));
        if (!text(params.get("expectedSeries")).equals(series) || current == null
                || !text(params.get("expectedValue")).equals(current)
                || candidate == null || !market.genesisParamAmplifies(paramKey, current, candidate))
            return ApiResult.fail(409, "G4_GENESIS_PARAM_STALE");
        String reason = text(command.params().get(REASON_FIELD));
        NexMarketValueUpdateRequest request = new NexMarketValueUpdateRequest(candidate, reason,
                context.operator(), text(params.get("decisionRef")), null, null, text(params.get("expectedValue")));
        return commands.updateParam(paramKey, context.idempotencyKey(), request);
    }

    private static boolean decisionRequired(String paramKey) {
        return Set.of("divBase", "airdropPct", "emissionCurve").contains(paramKey);
    }

    private static Map<String, Object> values(AuditReplayCommand command) {
        if (!supports(command)) throw new BizException(422, "G4_PARAM_PROPOSAL_INVALID");
        Map<String, Object> params = new LinkedHashMap<>(command.params());
        params.remove(MAKER_FIELD);
        params.remove(REASON_FIELD);
        Set<String> required = Set.of("paramKey", "value", "expectedValue", "expectedSeries");
        Set<String> allowed = new java.util.HashSet<>(required);
        allowed.add("decisionRef");
        if (!params.keySet().equals(required) && !params.keySet().equals(allowed))
            throw new BizException(422, "G4_PARAM_PROPOSAL_INVALID");
        for (String field : required) {
            if (text(params.get(field)).isBlank()) throw new BizException(422, "G4_PARAM_PROPOSAL_INVALID");
        }
        String paramKey = text(params.get("paramKey"));
        if (!AMPLIFY_KEYS.contains(paramKey)) throw new BizException(422, "G4_PARAM_PROPOSAL_INVALID");
        if (decisionRequired(paramKey) && text(params.get("decisionRef")).isBlank())
            throw new BizException(422, "G4_DECISION_REF_REQUIRED");
        return params;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static void requireText(String value, int min, int max, String code) {
        if (value == null || value.trim().length() < min || value.trim().length() > max)
            throw new BizException(422, code);
    }

    private static boolean hasAuthority(String authority) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authority != null && authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                        .anyMatch(grant -> authority.equals(grant.getAuthority()));
    }
}
