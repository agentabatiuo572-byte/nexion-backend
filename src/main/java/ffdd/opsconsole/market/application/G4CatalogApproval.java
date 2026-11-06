package ffdd.opsconsole.market.application;

import ffdd.opsconsole.market.application.GenesisCatalogService.TierChange;
import ffdd.opsconsole.market.application.GenesisCatalogService.TierRequest;
import ffdd.opsconsole.market.mapper.GenesisCatalogMapper.TierRow;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.domain.AuditLockTarget;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.platform.dto.AuditOperationProposalRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import ffdd.opsconsole.shared.security.AdminPermissionCache;
import ffdd.opsconsole.shared.security.SuperAdminAuthorization;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class G4CatalogApproval {
    public static final String PRICE_AUTHORITY = "finprod_g4_price_write";
    public static final AuditLockTarget TARGET = new AuditLockTarget("G", "genesis_catalog", "tiers");
    private static final String OP = "g4_genesis_tier_change";
    private static final Set<String> CREATE_FIELDS = Set.of("to", "priceUSDT", "expectedTiersVersion");
    private static final Set<String> UPDATE_FIELDS = Set.of("tierId", "to", "priceUSDT", "expectedTiersVersion");

    private final GenesisCatalogService catalog;
    private final AdminOperatorRoleResolver roles;
    private final AdminPermissionCache permissions;
    private final SuperAdminAuthorization superAdmins;

    public static boolean isOperation(AuditReplayCommand command) {
        return command != null && command.op() != null && OP.equalsIgnoreCase(command.op().trim());
    }

    public static boolean supports(AuditReplayCommand command) {
        return isOperation(command) && "G".equals(command.domain()) && OP.equals(command.op());
    }

    public static boolean isCatalogTarget(AuditLockTarget target) {
        return target != null && TARGET.domain().equalsIgnoreCase(String.valueOf(target.domain()).trim())
                && TARGET.type().equalsIgnoreCase(String.valueOf(target.type()).trim())
                && TARGET.id().equalsIgnoreCase(String.valueOf(target.id()).trim());
    }

    public boolean canPropose(AuditReplayCommand command) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return supports(command) && authentication != null && authentication.isAuthenticated()
                && authentication.getDetails() instanceof Map<?, ?> details && "ADMIN".equals(details.get("subjectType"))
                && authentication.getAuthorities().stream().anyMatch(a -> PRICE_AUTHORITY.equals(a.getAuthority()));
    }

    public AuditReplayCommand prepare(AuditOperationProposalRequest request, Long maker) {
        AuditReplayCommand command = request.command();
        if (!"G4".equals(request.sourceDomain()) || !TARGET.id().equals(request.obj()) || !TARGET.equals(request.target())
                || (request.targets() != null && !request.targets().isEmpty())) {
            throw new BizException(403, "A2_BUSINESS_CONTEXT_MISMATCH");
        }
        if (!canPropose(command) || !isActiveMaker(maker, false)) throw new BizException(403, "G4_CATALOG_MAKER_FORBIDDEN");
        Map<String, Object> params = command.params();
        if (params == null || (!CREATE_FIELDS.equals(params.keySet()) && !UPDATE_FIELDS.equals(params.keySet()))) throw invalid();
        String tierId = tierId(params);
        TierRequest tier = tierRequest(params, request.reason(), request.operator());
        TierChange change = catalog.prepareTierChange(tierId, tier);
        if (!change.amplifies()) throw new BizException(409, "G4_CATALOG_APPROVAL_NOT_REQUIRED");

        Map<String, Object> bound = new LinkedHashMap<>();
        if (tierId != null) bound.put("tierId", tierId);
        bound.put("to", tier.to());
        bound.put("priceUSDT", tier.priceUSDT().stripTrailingZeros().toPlainString());
        bound.put("expectedTiersVersion", tier.expectedTiersVersion());
        bound.put("makerAdminId", maker);
        bound.put("changesSupply", change.newSupply() != null);
        bound.put("sourceRevision", catalog.tierSourceRevision(change));
        bound.put("beforeSummary", summary(change, false));
        bound.put("afterSummary", summary(change, true));
        return new AuditReplayCommand("G", OP, bound);
    }

    public static boolean canReadOwnProposal(AuditReplayCommand command, long actor, Set<String> grants) {
        return isMaker(command, actor) && hasBusinessPermissions(grants, changesSupply(command));
    }

    public static boolean isMaker(AuditReplayCommand command, Long actor) {
        return actor != null && actor.equals(makerId(command));
    }

    public ApiResult<Void> validateChecker(AuditReplayCommand command, Long checker, boolean approving) {
        Long maker = makerId(command);
        if (checker == null || maker == null || !superAdmins.isSuperAdmin(SecurityContextHolder.getContext().getAuthentication())) {
            return ApiResult.fail(403, "G4_CATALOG_APPROVAL_FORBIDDEN");
        }
        Set<String> grants = permissions.getPermissionCodes(checker);
        boolean changesSupply = changesSupply(command);
        if (!grants.contains("platform_a2_operation_approve") || !hasBusinessPermissions(grants, changesSupply)) {
            return ApiResult.fail(403, "G4_CATALOG_APPROVAL_FORBIDDEN");
        }
        if (maker.equals(checker)) return ApiResult.fail(403, "A2_MAKER_CHECKER_REQUIRED");
        if (approving && !isActiveMaker(maker, changesSupply)) return ApiResult.fail(403, "G4_CATALOG_MAKER_FORBIDDEN");
        return ApiResult.ok();
    }

    public ApiResult<Void> replay(AuditReplayCommand command, AuditReplayContext context) {
        String operationId = A2ReplayContext.approvedGenesisCatalogId(command);
        if (operationId == null) return ApiResult.fail(403, "G4_CATALOG_APPROVAL_FORBIDDEN");
        Map<String, Object> params = command.params();
        TierRequest tier = tierRequest(params, (String) params.get("proposalReason"), context.operator());
        TierChange change = catalog.prepareTierChange(tierId(params), tier);
        if (!catalog.tierSourceRevision(change).equals(params.get("sourceRevision"))
                || !change.amplifies() || (change.newSupply() != null) != changesSupply(command)) {
            return ApiResult.fail(409, "G4_CATALOG_SNAPSHOT_CHANGED");
        }
        return catalog.applyTierChange(change, tier, operationId);
    }

    private boolean isActiveMaker(Long maker, boolean changesSupply) {
        return maker != null && roles.isActiveAdministrator(maker)
                && hasBusinessPermissions(permissions.getPermissionCodes(maker), changesSupply);
    }

    private static boolean hasBusinessPermissions(Set<String> grants, boolean changesSupply) {
        return grants.contains(PRICE_AUTHORITY) && (!changesSupply || grants.contains("finprod_g4_write"));
    }

    public static Long makerId(AuditReplayCommand command) {
        return command.params() != null && command.params().get("makerAdminId") instanceof Number maker
                ? maker.longValue() : null;
    }

    private static boolean changesSupply(AuditReplayCommand command) {
        return command.params() != null && Boolean.TRUE.equals(command.params().get("changesSupply"));
    }

    private static String tierId(Map<String, Object> params) {
        if (!params.containsKey("tierId")) return null;
        if (params.get("tierId") instanceof String id && !id.isBlank() && id.length() <= 32) return id;
        throw invalid();
    }

    private static TierRequest tierRequest(Map<String, Object> params, String reason, String operator) {
        Object price = params.get("priceUSDT");
        if (!(price instanceof String || price instanceof Integer || price instanceof Long)) throw invalid();
        long to = integer(params.get("to"));
        if (to > Integer.MAX_VALUE) throw invalid();
        try {
            return new TierRequest((int) to, new BigDecimal(price.toString()), integer(params.get("expectedTiersVersion")), reason, operator);
        } catch (NumberFormatException ex) {
            throw invalid();
        }
    }

    private static long integer(Object value) {
        if (value instanceof Integer number && number >= 0) return number;
        if (value instanceof Long number && number >= 0) return number;
        throw invalid();
    }

    private static String summary(TierChange change, boolean after) {
        var series = change.series();
        int supply = after && change.newSupply() != null ? change.newSupply() : series.totalSupply();
        String prefix = "系列 " + series.seriesCode() + " (#" + series.id() + ") · 总量 " + supply
                + " · 档位版本 " + (change.state().tiersVersion() + (after ? 1 : 0)) + " · ";
        if (change.createsTier() && !after) {
            return prefix + (change.before().isEmpty() ? "无档位" : "末档截止 " + change.before().get(change.index() - 1).rangeTo());
        }
        var tiers = after ? change.after() : change.before();
        String description = tierSummary(tiers.get(change.index()));
        int next = change.index() + 1;
        if (next < tiers.size() && !change.before().get(next).rangeFrom().equals(change.after().get(next).rangeFrom())) {
            description += " · " + tierSummary(tiers.get(next));
        }
        return prefix + description + (change.before().isEmpty() ? " · 市场关闭" : "");
    }

    private static String tierSummary(TierRow tier) {
        return tier.tierId() + " [" + tier.rangeFrom() + ", " + tier.rangeTo() + ") @ "
                + tier.priceUsdt().stripTrailingZeros().toPlainString() + " USDT";
    }

    private static BizException invalid() {
        return new BizException(422, "G4_CATALOG_PROPOSAL_INVALID");
    }
}
