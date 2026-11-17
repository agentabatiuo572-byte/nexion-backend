package ffdd.opsconsole.risk.application;

import ffdd.opsconsole.finance.application.EarningsReleaseService;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.domain.AuditLockTarget;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.platform.mapper.AuditObjectLockMapper;
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
public class K1RiskApproval {
    public static final String MAKER_AUTHORITY = "risk_k1_cluster_release";
    private static final String RELEASE_PARAM = "k1_release_param";
    private static final String EARNINGS_RELEASE = "k1_earnings_release";
    private final RiskReleaseParamsService releaseParams;
    private final EarningsReleaseService earningsRelease;
    private final SuperAdminAuthorization superAdmins;
    private final AuditObjectLockMapper lockMapper;

    public static boolean supports(AuditReplayCommand command) {
        return command != null && "K".equals(command.domain())
                && (RELEASE_PARAM.equals(command.op()) || EARNINGS_RELEASE.equals(command.op()));
    }

    public boolean canPropose(AuditReplayCommand command) {
        return supports(command) && hasAuthority(SecurityContextHolder.getContext().getAuthentication(), MAKER_AUTHORITY);
    }

    public AuditReplayCommand bindMaker(AuditReplayCommand command, Long makerId) {
        if (makerId == null || makerId <= 0) throw new BizException(403, "K1_MAKER_REQUIRED");
        target(command);
        if (!canPropose(command)) throw new BizException(403, "K1_MAKER_FORBIDDEN");
        Map<String, Object> params = new LinkedHashMap<>();
        Map<?, ?> proposalValues = values(command);
        params.put("values", proposalValues);
        params.put("makerAdminId", makerId);
        if (RELEASE_PARAM.equals(command.op())) {
            params.put("before", releaseParams.approvalBefore(
                    String.valueOf(proposalValues.get("key")), longValue(proposalValues.get("expectedVersion"))));
        } else {
            params.put("before", earningsRelease.approvalBefore(
                    String.valueOf(proposalValues.get("entryNo"))));
        }
        return new AuditReplayCommand("K", command.op(), params);
    }

    public ApiResult<Void> validateChecker(AuditReplayCommand command, Long checkerId) {
        Object maker = command.params() == null ? null : command.params().get("makerAdminId");
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(maker instanceof Number) || checkerId == null || checkerId <= 0
                || !String.valueOf(checkerId).equals(authentication == null ? "" : authentication.getName())
                || !superAdmins.isSuperAdmin(authentication)
                || !hasAuthority(authentication, "platform_a2_operation_approve")) {
            return ApiResult.fail(403, "K1_APPROVAL_FORBIDDEN");
        }
        return String.valueOf(checkerId).equals(String.valueOf(maker))
                ? ApiResult.fail(403, "A2_MAKER_CHECKER_REQUIRED") : ApiResult.ok();
    }

    public static AuditLockTarget target(AuditReplayCommand command) {
        Map<?, ?> values = values(command);
        if (RELEASE_PARAM.equals(command.op())) {
            String key = String.valueOf(values.get("key"));
            if (!Set.of("duplicateAccountPendingFrom", "duplicateAccountFreezeFrom").contains(key)) {
                throw new BizException(422, "K1_RELEASE_PARAM_APPROVAL_KEY_UNSUPPORTED");
            }
            longValue(values.get("expectedVersion"));
            return new AuditLockTarget("K", "release_param", key);
        }
        String entryNo = String.valueOf(values.get("entryNo"));
        if (entryNo.isBlank() || !entryNo.equals(entryNo.trim()) || entryNo.length() > 64) {
            throw new BizException(422, "K1_EARNINGS_RELEASE_ENTRY_REQUIRED");
        }
        return new AuditLockTarget("K", "earnings_release", entryNo);
    }

    public record Review(String action, String before, String after) {}

    public ApiResult<Review> preview(AuditReplayCommand command, String reason) {
        Map<?, ?> values = values(command);
        if (RELEASE_PARAM.equals(command.op())) {
            var review = releaseParams.previewApproval(
                    String.valueOf(values.get("key")), String.valueOf(values.get("value")),
                    longValue(values.get("expectedVersion")));
            return ApiResult.ok(new Review(review.action(), review.before(), review.after()));
        }
        var review = earningsRelease.previewApproval(String.valueOf(values.get("entryNo")));
        return ApiResult.ok(new Review(review.action(), review.before(), review.after()));
    }

    public ApiResult<?> replay(AuditReplayCommand command, AuditReplayContext context,
                               Long checkerId, String operationId) {
        ApiResult<Void> checker = validateChecker(command, checkerId);
        if (checker.getCode() != 0) return checker;
        if (!A2ReplayContext.isReplaying() || operationId == null || !operationId.equals(A2ReplayContext.operationId())) {
            return ApiResult.fail(403, "K1_APPROVAL_FORBIDDEN");
        }
        AuditLockTarget target = target(command);
        var locks = lockMapper.selectActiveByTicketId(operationId);
        if (locks.size() != 1 || !operationId.equals(locks.get(0).getTicketId()) || !target.equals(
                new AuditLockTarget(locks.get(0).getTargetDomain(), locks.get(0).getTargetType(), locks.get(0).getTargetId()))) {
            return ApiResult.fail(403, "K1_APPROVAL_FORBIDDEN");
        }
        if (!(command.params().get("proposalReason") instanceof String reason)) {
            return ApiResult.fail(422, "K1_PROPOSAL_INVALID");
        }
        if (!(command.params().get("before") instanceof Map<?, ?> before)) {
            return ApiResult.fail(422, "K1_PROPOSAL_INVALID");
        }
        Map<?, ?> values = values(command);
        if (RELEASE_PARAM.equals(command.op())) {
            return releaseParams.applyApproved(
                    String.valueOf(values.get("key")), String.valueOf(values.get("value")),
                    longValue(values.get("expectedVersion")), reason, context.operator(), before);
        }
        return earningsRelease.applyApproved(
                String.valueOf(values.get("entryNo")), reason, context.operator(), before);
    }

    private static Map<?, ?> values(AuditReplayCommand command) {
        if (!supports(command) || command.params() == null)
            throw new BizException(422, "K1_PROPOSAL_INVALID");
        if (!(command.params().get("values") instanceof Map<?, ?> values)) {
            throw new BizException(422, "K1_PROPOSAL_INVALID");
        }
        Set<String> keys = RELEASE_PARAM.equals(command.op())
                ? Set.of("key", "value", "expectedVersion")
                : Set.of("entryNo");
        if (!values.keySet().equals(keys)) throw new BizException(422, "K1_PROPOSAL_INVALID");
        return values;
    }

    private static long longValue(Object value) {
        if (!(value instanceof Number number) || number.longValue() < 0) {
            throw new BizException(422, "EXPECTED_VERSION_REQUIRED");
        }
        return number.longValue();
    }

    private static boolean hasAuthority(Authentication authentication, String authority) {
        return authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream().anyMatch(grant -> authority.equals(grant.getAuthority()));
    }
}
