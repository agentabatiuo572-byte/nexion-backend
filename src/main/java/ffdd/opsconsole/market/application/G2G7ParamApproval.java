package ffdd.opsconsole.market.application;

import ffdd.opsconsole.market.dto.ExchangeParamUpdateRequest;
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
 * BUG-277: G2 兑换参数与 G7 回购参数的「放大资金流出」方向必须由另一位超管批准后回放执行。
 *
 * <p>决策 T02 要求放大方向提交审批、另一人确认后生效;决策 T11 要求按拟改后的值检查覆盖率。
 * 收紧方向仍然立即生效,不经过本组件。</p>
 *
 * <p>服务端在提案时把当前值快照写进 {@code expectedValue},批准回放时旧值必须仍然成立,否则这次批准作废。
 * G2 的确定新增应付 Δ 只算审批时真实排队中的单按新费率多付的净额;G7 只影响新开仓,Δ 为 0。</p>
 */
@Component
@RequiredArgsConstructor
public class G2G7ParamApproval {
    public static final String G2 = "g2_exchange_param";
    public static final String G7 = "g7_repurchase_param";
    private static final String MAKER_FIELD = "makerAdminId";
    private static final String REASON_FIELD = "proposalReason";
    private static final String TARGET_TYPE_G2 = "exchange_param";
    private static final String TARGET_TYPE_G7 = "repurchase_param";

    private final OpsNexMarketService market;
    private final G2G3AdminCommandService exchangeCommands;
    private final OpsRepurchaseAdminService repurchaseCommands;
    private final SuperAdminAuthorization superAdmins;
    private final AuditObjectLockMapper locks;

    /** 只接管参数键确实存在放大方向的命令;收紧方向与其它 G 域命令继续走原路径。 */
    public static boolean supports(AuditReplayCommand command) {
        if (command == null || !"G".equals(command.domain()) || command.op() == null || command.params() == null) {
            return false;
        }
        String paramKey = text(command.params().get("paramKey"));
        return switch (command.op()) {
            case G2 -> OpsNexMarketService.G2_AMPLIFY_KEYS.contains(paramKey);
            case G7 -> OpsNexMarketService.G7_AMPLIFY_KEYS.contains(paramKey);
            default -> false;
        };
    }

    /** 与直写入口完全一致的逐参数业务权限码。 */
    public static String makerAuthority(AuditReplayCommand command) {
        if (!supports(command)) {
            return null;
        }
        String paramKey = text(command.params().get("paramKey"));
        return G2.equals(command.op())
                ? OpsNexMarketService.exchangeParamAuthority(paramKey)
                : OpsNexMarketService.repurchaseParamAuthority(paramKey);
    }

    /** 审计归属域:与直写页面一致,G2 兑换 / G7 回购。 */
    public static String sourceDomain(AuditReplayCommand command) {
        if (!supports(command)) {
            return null;
        }
        return G2.equals(command.op()) ? "G2" : "G7";
    }

    /** 提案标题(带参数键),供审批列表区分具体参数。 */
    public static String proposalAction(AuditReplayCommand command) {
        if (!supports(command)) {
            return null;
        }
        return (G2.equals(command.op()) ? "兑换参数调整 · " : "回购参数调整 · ")
                + text(command.params().get("paramKey"));
    }

    public static Set<String> makerAuthorities(AuditReplayCommand command) {
        String authority = makerAuthority(command);
        return authority == null ? Set.of() : Set.of(authority);
    }

    /** 规范锁目标,与 PC 提案里的 {@code {domain:"G",type:"exchange_param"|"repurchase_param",id:paramKey}} 对齐。 */
    public static AuditLockTarget target(AuditReplayCommand command) {
        if (!supports(command)) {
            return null;
        }
        String paramKey = text(command.params().get("paramKey"));
        return new AuditLockTarget("G", G2.equals(command.op()) ? TARGET_TYPE_G2 : TARGET_TYPE_G7, paramKey);
    }

    public boolean canPropose(AuditReplayCommand command) {
        return hasAuthority(makerAuthority(command));
    }

    /** 规范化命令:丢掉客户端多余字段,补上服务端绑定的发起人和旧值快照。 */
    public AuditReplayCommand bindMaker(AuditReplayCommand command, Long makerId) {
        if (makerId == null || makerId <= 0) {
            throw new BizException(403, "G2G7_PARAM_MAKER_REQUIRED");
        }
        if (!supports(command)) {
            throw new BizException(422, "G2G7_PARAM_PROPOSAL_INVALID");
        }
        String current = currentValue(command);
        if (current == null) {
            throw new BizException(422, "G2G7_PARAM_KEY_INVALID");
        }
        String candidate = candidateValue(command, text(command.params().get("value")));
        if (candidate == null) {
            throw new BizException(422, "G2G7_PARAM_VALUE_INVALID");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("paramKey", text(command.params().get("paramKey")));
        // Persist the server-normalized candidate (e.g. 13% -> 13) so preview, approval and the
        // official replay service all read the same value.
        params.put("value", candidate);
        params.put("expectedValue", current);
        params.put("makerAdminId", makerId);
        return new AuditReplayCommand("G", command.op(), Map.copyOf(params));
    }

    public ApiResult<Void> validateChecker(AuditReplayCommand command, Long checkerId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object maker = command == null || command.params() == null ? null : command.params().get(MAKER_FIELD);
        // 与 G3 同一批准口径:另一位在职超管 + A2 操作批准权;发起人必须与批准人不同。
        if (!(maker instanceof Number) || checkerId == null || checkerId <= 0
                || !String.valueOf(checkerId).equals(authentication == null ? "" : authentication.getName())
                || !superAdmins.isSuperAdmin(authentication)
                || !hasAuthority("platform_a2_operation_approve")) {
            return ApiResult.fail(403, "G2G7_PARAM_APPROVAL_FORBIDDEN");
        }
        return String.valueOf(checkerId).equals(String.valueOf(maker))
                ? ApiResult.fail(403, "A2_MAKER_CHECKER_REQUIRED") : ApiResult.ok();
    }

    /** 提案预览:确认旧值未变、方向确实是放大、并按拟改值加 Δ 复核覆盖率。 */
    public Map<String, Object> preview(AuditReplayCommand command, String reason) {
        Map<String, Object> params = values(command);
        requireText(reason, 8, 200, "REASON_LENGTH_INVALID");
        String paramKey = text(params.get("paramKey"));
        String current = currentValue(command);
        if (current == null) {
            throw new BizException(422, "G2G7_PARAM_KEY_INVALID");
        }
        if (!text(params.get("expectedValue")).equals(current)) {
            throw new BizException(409, staleCode(command));
        }
        String candidate = candidateValue(command, text(params.get("value")));
        if (candidate == null) {
            throw new BizException(422, "G2G7_PARAM_VALUE_INVALID");
        }
        if (!amplifies(command, current, candidate)) {
            throw new BizException(409, "G2G7_PARAM_NOT_AMPLIFYING");
        }
        BigDecimal delta = G2.equals(command.op())
                ? market.exchangeParamLiabilityIncrease(paramKey, current, candidate) : BigDecimal.ZERO;
        market.requireCoverageRedline(delta);
        Map<String, Object> review = new LinkedHashMap<>();
        review.put("before", paramKey + " = " + current);
        review.put("after", paramKey + " = " + candidate + " · 确定新增应付 Δ " + delta.toPlainString() + " USDT");
        return review;
    }

    public ApiResult<?> replay(AuditReplayCommand command, AuditReplayContext context, Long checkerId,
                               String operationId) {
        ApiResult<Void> checker = validateChecker(command, checkerId);
        if (checker.getCode() != 0) {
            return checker;
        }
        if (!A2ReplayContext.isReplaying() || operationId == null
                || !operationId.equals(A2ReplayContext.operationId())) {
            return ApiResult.fail(403, "G2G7_PARAM_APPROVAL_FORBIDDEN");
        }
        AuditLockTarget expected = target(command);
        var owned = locks.selectActiveByTicketId(operationId);
        if (expected == null || owned == null || owned.size() != 1
                || !operationId.equals(owned.get(0).getTicketId())
                || !expected.equals(new AuditLockTarget(owned.get(0).getTargetDomain(),
                        owned.get(0).getTargetType(), owned.get(0).getTargetId()))) {
            return ApiResult.fail(403, "G2G7_PARAM_APPROVAL_FORBIDDEN");
        }
        Map<String, Object> params = values(command);
        String reason = text(command.params().get(REASON_FIELD));
        String paramKey = text(params.get("paramKey"));
        String value = text(params.get("value"));
        String expectedValue = text(params.get("expectedValue"));
        // Approval replay revalidates the exact old value bound at proposal time. G7's official service
        // does not carry expectedValue, so this check must happen before it; G2 also keeps its own check.
        String current = currentValue(command);
        if (current == null || !expectedValue.equals(current)) {
            return ApiResult.fail(409, staleCode(command));
        }
        String candidate = candidateValue(command, value);
        if (candidate == null) {
            return ApiResult.fail(422, "G2G7_PARAM_VALUE_INVALID");
        }
        value = candidate;
        // 回放走与直写同一正式服务:旧值快照校验、放大方向复核、覆盖率、落库、审计和业务事件都只做一次。
        return G2.equals(command.op())
                ? exchangeCommands.exchangeParam(context.idempotencyKey(), paramKey,
                        new ExchangeParamUpdateRequest(value, reason, context.operator(), expectedValue))
                : repurchaseCommands.update(paramKey, context.idempotencyKey(),
                        new OpsRepurchaseAdminService.UpdateRequest(value, reason, context.operator(), null));
    }

    private String currentValue(AuditReplayCommand command) {
        String paramKey = text(command.params() == null ? null : command.params().get("paramKey"));
        return G2.equals(command.op())
                ? market.exchangeParamCurrentValue(paramKey)
                : market.repurchaseParamCurrentValue(paramKey);
    }

    private String candidateValue(AuditReplayCommand command, String rawValue) {
        String paramKey = text(command.params() == null ? null : command.params().get("paramKey"));
        return G2.equals(command.op())
                ? market.exchangeParamCandidateValue(paramKey, rawValue)
                : market.repurchaseParamCandidateValue(paramKey, rawValue);
    }

    private boolean amplifies(AuditReplayCommand command, String oldValue, String newValue) {
        String paramKey = text(command.params() == null ? null : command.params().get("paramKey"));
        return G2.equals(command.op())
                ? market.exchangeParamAmplifies(paramKey, oldValue, newValue)
                : market.repurchaseParamAmplifies(paramKey, oldValue, newValue);
    }

    private static String staleCode(AuditReplayCommand command) {
        return G2.equals(command.op()) ? "G2_EXCHANGE_PARAM_STALE" : "G7_REPURCHASE_PARAM_STALE";
    }

    private static Map<String, Object> values(AuditReplayCommand command) {
        if (!supports(command)) {
            throw new BizException(422, "G2G7_PARAM_PROPOSAL_INVALID");
        }
        Map<String, Object> params = new LinkedHashMap<>(command.params());
        params.remove(MAKER_FIELD);
        params.remove(REASON_FIELD);
        if (!params.keySet().equals(Set.of("paramKey", "value", "expectedValue"))) {
            throw new BizException(422, "G2G7_PARAM_PROPOSAL_INVALID");
        }
        String value = text(params.get("value"));
        if (value.isBlank() || value.length() > 64 || text(params.get("expectedValue")).isBlank()) {
            throw new BizException(422, "G2G7_PARAM_PROPOSAL_INVALID");
        }
        return params;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static void requireText(String value, int min, int max, String code) {
        if (value == null || value.trim().length() < min || value.trim().length() > max) {
            throw new BizException(422, code);
        }
    }

    private static boolean hasAuthority(String authority) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authority != null && authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                        .anyMatch(grant -> authority.equals(grant.getAuthority()));
    }
}
