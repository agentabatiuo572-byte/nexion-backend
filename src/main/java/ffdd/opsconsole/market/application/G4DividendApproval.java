package ffdd.opsconsole.market.application;

import ffdd.opsconsole.market.dto.NexMarketValueUpdateRequest;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.domain.AuditLockTarget;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.platform.mapper.AuditObjectLockMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.SuperAdminAuthorization;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class G4DividendApproval {
    public static final String PARAM = "g4_genesis_param";
    public static final String EMISSION = "g4_genesis_rerun_dividend";
    private final G4DividendPolicy policy;
    private final G4AdminCommandService commands;
    private final SuperAdminAuthorization superAdmins;
    private final AuditObjectLockMapper locks;

    public static boolean supports(AuditReplayCommand command) {
        return command != null && "G".equals(command.domain()) && (EMISSION.equals(command.op())
                || (PARAM.equals(command.op()) && command.params() != null && "dividend".equals(command.params().get("paramKey"))));
    }
    public static String makerAuthority(AuditReplayCommand command) {
        return !supports(command) ? null : PARAM.equals(command.op()) ? "finprod_g4_dividend_rate_write" : "finprod_g4_write";
    }
    public boolean canPropose(AuditReplayCommand command) { return has(makerAuthority(command)); }
    public static AuditLockTarget target(AuditReplayCommand command) {
        return new AuditLockTarget("G", PARAM.equals(command.op()) ? "genesis_param" : "genesis_dividend_batch",
                PARAM.equals(command.op()) ? "dividend" : text(command.params(), "batchNo"));
    }

    public AuditReplayCommand bindMaker(AuditReplayCommand command, Long maker) {
        if (maker == null || maker <= 0) throw new BizException(403, "G4_MAKER_REQUIRED");
        Map<String,Object> params = new LinkedHashMap<>(values(command));
        params.put("makerAdminId", maker);
        return new AuditReplayCommand("G", command.op(), params);
    }

    public ApiResult<Void> validateChecker(AuditReplayCommand command, Long checker) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        Object maker = command.params() == null ? null : command.params().get("makerAdminId");
        if (!(maker instanceof Number) || checker == null || !String.valueOf(checker).equals(authentication == null ? "" : authentication.getName())
                || !superAdmins.isSuperAdmin(authentication) || !has("platform_a2_operation_approve") || !has(makerAuthority(command)))
            return ApiResult.fail(403, "G4_APPROVAL_FORBIDDEN");
        return String.valueOf(checker).equals(maker.toString()) ? ApiResult.fail(403, "A2_MAKER_CHECKER_REQUIRED") : ApiResult.ok();
    }

    @Transactional
    public Map<String,Object> state(String batchNo) {
        G4DividendPolicy.State state = policy.state(batchNo);
        return Map.of("expectedRevision", state.revision(), "seriesId", state.series().id(), "seriesCode", state.series().seriesCode(),
                "dividend", G4DividendPolicy.number(state.series().dailyEmissionRatePct()), "amountUsdt", state.amount(), "count", state.count(),
                "completed", state.batch() != null && "COMPLETED".equals(state.batch().status()));
    }

    @Transactional
    public Map<String,Object> preview(AuditReplayCommand command, String reason) {
        Map<String,Object> params = values(command);
        requireText(reason, 8, 200, "REASON_LENGTH_INVALID");
        String batchNo = EMISSION.equals(command.op()) ? text(params,"batchNo") : null;
        var state = policy.state(batchNo);
        if (!state.revision().equals(text(params,"expectedRevision"))) throw new BizException(409,"G4_DIVIDEND_STALE");
        String before, after;
        if (PARAM.equals(command.op())) {
            BigDecimal value = normalized(text(params,"value"));
            policy.previewDividend(state,value);
            before = "series="+state.series().seriesCode()+"; dividend="+G4DividendPolicy.number(state.series().dailyEmissionRatePct());
            after = G4DividendPolicy.number(value)+"%; decisionRef="+text(params,"decisionRef");
        } else {
            if (state.batch() != null && "COMPLETED".equals(state.batch().status())) throw new BizException(409,"G4_EMISSION_ALREADY_COMPLETED");
            if (state.amount().signum() <= 0) throw new BizException(422,"G4_EMISSION_AMOUNT_UNAVAILABLE");
            policy.requireCoverage(state.amount());
            before = "series="+state.series().seriesCode()+"; batch="+batchNo;
            after = "pay="+state.count()+"; USDT="+state.amount().toPlainString()+"; decisionRef="+text(params,"decisionRef");
        }
        return Map.of("before",before,"after",after);
    }

    public ApiResult<?> replay(AuditReplayCommand command, AuditReplayContext context, Long checker, String operationId) {
        var valid = validateChecker(command,checker);
        if (valid.getCode()!=0) return valid;
        if (!A2ReplayContext.isReplaying() || operationId == null || !operationId.equals(A2ReplayContext.operationId()))
            return ApiResult.fail(403,"G4_APPROVAL_FORBIDDEN");
        var target = target(command); var owned = locks.selectActiveByTicketId(operationId);
        if (owned.size()!=1 || !operationId.equals(owned.get(0).getTicketId()) || !target.equals(new AuditLockTarget(
                owned.get(0).getTargetDomain(),owned.get(0).getTargetType(),owned.get(0).getTargetId())))
            return ApiResult.fail(403,"G4_APPROVAL_FORBIDDEN");
        Map<String,Object> params = values(command);
        String reason = text(command.params(),"proposalReason");
        var request = new NexMarketValueUpdateRequest(PARAM.equals(command.op()) ? text(params,"value") : null,
                reason, context.operator(),text(params,"decisionRef"),null,null);
        String action = PARAM.equals(command.op()) ? "dividend" : "emission:"+text(params,"batchNo");
        return policy.approved(action,text(params,"expectedRevision"),()->PARAM.equals(command.op())
                ? commands.updateParam("dividend",context.idempotencyKey(),request)
                : commands.rerunEmission(context.idempotencyKey(),text(params,"batchNo"),request));
    }

    static BigDecimal normalized(String value) {
        try {
            BigDecimal number = new BigDecimal(value.trim().replace(",", "").replaceAll("[^0-9.\\-]", ""));
            if (number.signum()<0 || number.compareTo(BigDecimal.valueOf(5))>0) throw new NumberFormatException();
            return number.setScale(6,RoundingMode.HALF_UP);
        } catch (NumberFormatException error) { throw new BizException(422,"G4_GENESIS_PARAM_VALUE_INVALID"); }
    }
    private static Map<String,Object> values(AuditReplayCommand command) {
        if (!supports(command) || command.params()==null) throw new BizException(422,"G4_PROPOSAL_INVALID");
        Map<String,Object> params = new LinkedHashMap<>(command.params());
        params.remove("makerAdminId"); params.remove("proposalReason");
        G4DividendPolicy.requireDecision(params.get("decisionRef") instanceof String decision ? decision : null);
        Set<String> expected = PARAM.equals(command.op()) ? Set.of("paramKey","value","decisionRef","expectedRevision")
                : Set.of("batchNo","decisionRef","expectedRevision");
        if (!params.keySet().equals(expected) || !text(params,"expectedRevision").matches("[0-9a-f]{64}"))
            throw new BizException(422,"G4_PROPOSAL_INVALID");
        return params;
    }
    private static String text(Map<?,?> values,String key) {
        Object value=values.get(key);
        if (!(value instanceof String text) || text.isBlank()) throw new BizException(422,"G4_PROPOSAL_INVALID");
        return text.trim();
    }
    private static void requireText(String value,int min,int max,String code) {
        if (value==null || value.trim().length()<min || value.trim().length()>max) throw new BizException(422,code);
    }
    private static boolean has(String authority) {
        Authentication auth=SecurityContextHolder.getContext().getAuthentication();
        return authority!=null && auth!=null && auth.isAuthenticated() && auth.getAuthorities().stream().anyMatch(a->authority.equals(a.getAuthority()));
    }
}
