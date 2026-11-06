package ffdd.opsconsole.platform.application;

import ffdd.opsconsole.emergency.application.OpsKillSwitchService;

import ffdd.opsconsole.content.application.LearningCourseActivationApproval;
import ffdd.opsconsole.content.application.OpsI18nLearningService;

import ffdd.opsconsole.device.application.E2TaskPricingApproval;
import ffdd.opsconsole.growth.application.H8RewardParamApproval;
import ffdd.opsconsole.growth.application.H5GrowthApproval;
import ffdd.opsconsole.growth.application.H3GrowthApproval;
import ffdd.opsconsole.growth.application.H4GrowthApproval;
import ffdd.opsconsole.growth.application.H7VoucherDisposition;
import ffdd.opsconsole.risk.application.K2OtpInitialApproval;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.infrastructure.AuditOperationTicketEntity;
import ffdd.opsconsole.platform.mapper.AuditOperationTicketMapper;
import ffdd.opsconsole.shared.audit.AuditLogQueryRequest;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import ffdd.opsconsole.treasury.application.TreasuryThresholdApproval;
import ffdd.opsconsole.team.application.TeamFundApproval;
import ffdd.opsconsole.market.application.G3MarketApproval;
import ffdd.opsconsole.market.application.G4CatalogApproval;
import ffdd.opsconsole.market.application.G4DividendApproval;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Server-side A2 row visibility. UI controls are never an authorization boundary. */
@Component
@RequiredArgsConstructor
public class A2AccessPolicy {
    private static final List<String> DOMAIN_ORDER =
            List.of("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M");
    private static final Map<String, String> DOMAIN_AUTHORITY_PREFIX = Map.ofEntries(
            Map.entry("A", "platform"),
            Map.entry("B", "overview"),
            Map.entry("C", "user"),
            Map.entry("D", "finance"),
            Map.entry("E", "device"),
            Map.entry("F", "network"),
            Map.entry("G", "finprod"),
            Map.entry("H", "growth"),
            Map.entry("I", "content"),
            Map.entry("J", "emergency"),
            Map.entry("K", "risk"),
            Map.entry("L", "bi"),
            Map.entry("M", "service"));
    private static final Pattern BUSINESS_AUTHORITY =
            Pattern.compile("^([a-z]+)_([a-m])(\\d+)_", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPERATION_ID =
            Pattern.compile("^WO-[A-Z0-9]+(?:-[A-Z0-9]+)*$");
    private final AdminOperatorRoleResolver roleResolver;
    private final AuditOperationTicketMapper ticketMapper;
    private final ffdd.opsconsole.shared.security.AdminPermissionCache permissions;
    @Autowired
    private AuditReplayBusinessPermissionGuard replayBusinessPermissionGuard;

    /** Rechecks current DB authority even when invoked outside the HTTP filter. */
    public long requireProposalReader() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getDetails() instanceof Map<?, ?> details)
                || !"ADMIN".equals(details.get("subjectType")))
            throw new ffdd.opsconsole.shared.exception.BizException(403, "A2_PROPOSAL_READ_FORBIDDEN");
        try {
            long id = Long.parseLong(auth.getName());
            if (roleResolver.isActiveAdministrator(id)) {
                Set<String> grants = permissions.getPermissionCodes(id);
                if (grants.contains("platform_a2_read") || grants.contains("platform_a2_proposal_create")
                        || (grants.contains("platform_a2_write")
                            && (grants.contains("user_c2_session_revoke_all") || grants.contains("user_c5_session_revoke_all")))
                        || grants.stream().anyMatch(AuditReplayBusinessPermissionGuard.F5_ACTION_AUTHORITIES::contains)
                        || grants.contains(A4RegistryApproval.MAKER_AUTHORITY)
                        || (grants.contains(OpsKillSwitchService.RESTORE_AUTHORITY) && grants.contains("platform_a2_write"))
                        || grants.contains("overview_b1_redline_write")
                        || LearningCourseActivationApproval.hasMakerAuthorities(grants)
                        || canReadOwnCourseRewardProposal(grants)
                        || grants.contains("overview_b5_threshold_write")
                        || grants.contains(E2TaskPricingApproval.MAKER_AUTHORITY)
                        || grants.contains(H8RewardParamApproval.MAKER_AUTHORITY)
                        || grants.contains(H5GrowthApproval.MAKER_AUTHORITY)
                        || grants.contains(H3GrowthApproval.MAKER_AUTHORITY)
                        || grants.contains(H4GrowthApproval.MAKER_AUTHORITY) || grants.contains(H4GrowthApproval.GUARD_AUTHORITY)
                        || H7VoucherDisposition.canRecover(grants)
                        || (grants.contains(K2OtpInitialApproval.MAKER_AUTHORITY)
                            && (grants.contains("platform_a2_proposal_create") || grants.contains("platform_a2_write")))
                        || grants.contains("network_f1_write") || grants.contains("network_f3_write") || grants.contains("network_f4_pool_fund")
                        || grants.contains("finprod_g3_curve_target_price_write") || grants.contains("finprod_g3_curve_pump_prob_write")
                        || grants.contains("finprod_g3_override_price_write") || grants.contains("finprod_g3_write")
                        || grants.contains("finprod_g4_dividend_rate_write") || grants.contains("finprod_g4_write")
                        || grants.contains(G4CatalogApproval.PRICE_AUTHORITY)) return id;
            }
        } catch (NumberFormatException ignored) { }
        throw new ffdd.opsconsole.shared.exception.BizException(403, "A2_PROPOSAL_READ_FORBIDDEN");
    }

    boolean canDecide(Long actorId) {
        return actorId != null && A2ReplayContext.hasAuthority("platform_a2_operation_approve")
                && roleResolver.isActiveAdministrator(actorId);
    }

    public Scope current() {
        String role = roleResolver.resolveCode();
        String actor = AdminActorResolver.resolve(null);
        return scope(role, actor, authorityDomains());
    }

    String authenticatedOperatorRoleLabel() {
        return roleResolver.resolve();
    }

    private Scope scope(String role, String actor, List<String> domains) {
        return switch (role == null ? "" : role.toUpperCase(Locale.ROOT)) {
            case "SUPER_ADMIN", "AUDITOR" -> new Scope(role, actor, null, false);
            case "FINANCE" -> new Scope(role, actor, List.of("D"), false);
            case "RISK" -> new Scope(role, actor, List.of("C", "K"), false);
            case "GROWTH" -> new Scope(role, actor, List.of("E", "H"), false);
            case "CONTENT" -> new Scope(role, actor, List.of("I"), false);
            case "SUPPORT" -> new Scope(role, actor, null, true);
            default -> new Scope(role, actor, domains, false);
        };
    }

    public boolean canAccessRecoveredProposal(AuditOperationTicketEntity ticket, AuditReplayCommand command, long actorId) {
        if (ticket == null || !StringUtils.hasText(ticket.getSourceDomain())) return false;
        Set<String> grants = permissions.getPermissionCodes(actorId);
        String domain = ticket.getSourceDomain().trim().substring(0, 1).toUpperCase(Locale.ROOT);
        // Exact maker ID was already matched by the durable proposal lookup.
        if (!domain.matches("[A-M]")) return false;
        if ("C".equals(domain) && AuditReplayBusinessPermissionGuard.isUserSessionRevocation(command)) {
            return (grants.contains("platform_a2_proposal_create") || grants.contains("platform_a2_write"))
                    && (grants.contains("user_c2_session_revoke_all") || grants.contains("user_c5_session_revoke_all"));
        }
        if (H7VoucherDisposition.isOperation(command)) {
            return "H".equals(domain) && H7VoucherDisposition.canRecover(grants);
        }
        if ("J".equals(domain) && OpsKillSwitchService.isRestoreCommand(command)) {
            return grants.contains(OpsKillSwitchService.RESTORE_AUTHORITY)
                    && (grants.contains("platform_a2_proposal_create") || grants.contains("platform_a2_write"));
        }
        if ("E".equals(domain) && AuditReplayBusinessPermissionGuard.isE4Refund(command)
                && grants.contains("platform_a2_proposal_create")
                && grants.contains(AuditReplayBusinessPermissionGuard.E4_REFUND_AUTHORITY)) return true;
        if ("F".equals(domain) && replayBusinessPermissionGuard.canRecoverF5Proposal(command, grants)) return true;
        if (!grants.contains("platform_a2_read")) {
            return grants.contains("platform_a2_proposal_create")
                    && (!StringUtils.hasText(ticket.getCommandJson()) || ("D".equals(domain)
                        && replayBusinessPermissionGuard.canReadOwnFinanceProposal(command, actorId, grants)));
        }
        var scope = scope(roleResolver.resolveCode(), AdminActorResolver.resolve(null), authorityDomains(grants));
        return scope.ownActorOnly() || scope.canSee(domain, ticket.getOperatorName());
    }

    boolean canAccessOwnBusinessProposal(AuditReplayCommand command, long actorId) {
        if (A4RegistryApproval.supports(command)) {
            return permissions.getPermissionCodes(actorId).contains(A4RegistryApproval.MAKER_AUTHORITY);
        }
        if (OpsI18nLearningService.isCourseRewardAdjustment(command)) {
            var review = OpsI18nLearningService.CourseRewardReview.fromCommand(command);
            return review != null && review.makerAdminId() == actorId
                    && canReadOwnCourseRewardProposal(permissions.getPermissionCodes(actorId));
        }
        if (G4CatalogApproval.supports(command)) {
            return G4CatalogApproval.canReadOwnProposal(command, actorId, permissions.getPermissionCodes(actorId));
        }
        if (LearningCourseActivationApproval.supports(command)) {
            return Long.valueOf(actorId).equals(LearningCourseActivationApproval.makerId(command))
                    && LearningCourseActivationApproval.hasMakerAuthorities(permissions.getPermissionCodes(actorId));
        }
        String authority;
        if (TreasuryThresholdApproval.supports(command)) {
            authority = TreasuryThresholdApproval.makerAuthority(command);
        } else if (E2TaskPricingApproval.supports(command)) {
            authority = E2TaskPricingApproval.MAKER_AUTHORITY;
        } else if (H4GrowthApproval.supports(command)) {
            authority = H4GrowthApproval.makerAuthority(command);
        } else if (H3GrowthApproval.supports(command)) {
            authority = H3GrowthApproval.MAKER_AUTHORITY;
        } else if (H5GrowthApproval.supports(command)) {
            authority = H5GrowthApproval.MAKER_AUTHORITY;
        } else if (H8RewardParamApproval.supports(command)) {
            authority = H8RewardParamApproval.MAKER_AUTHORITY;
        } else if (K2OtpInitialApproval.supports(command)) {
            authority = K2OtpInitialApproval.MAKER_AUTHORITY;
        } else if (TeamFundApproval.supports(command)) {
            authority = TeamFundApproval.makerAuthority(command);
        } else if (G4DividendApproval.supports(command)) {
            authority = G4DividendApproval.makerAuthority(command);
        } else if (G3MarketApproval.supports(command)) {
            authority = G3MarketApproval.makerAuthority(command);
        } else {
            return false;
        }
        if (command.params() == null) return false;
        Object makerId = command.params().get("makerAdminId");
        return makerId instanceof Number && Long.toString(actorId).equals(makerId.toString())
                && permissions.getPermissionCodes(actorId).contains(authority);
    }

    private static boolean canReadOwnCourseRewardProposal(Set<String> grants) {
        return grants.contains("content_i7_course_reward_adjust")
                && (grants.contains("platform_a2_write") || grants.contains("platform_a2_proposal_create"));
    }

    public AuditLogQueryRequest constrain(AuditLogQueryRequest request) {
        AuditLogQueryRequest query = request == null ? new AuditLogQueryRequest() : request;
        query.setAllowedDomains(null);
        if (StringUtils.hasText(query.getDomain())) {
            String domain = query.getDomain().trim().toUpperCase(Locale.ROOT);
            if (!domain.matches("[A-M]")) {
                query.setDomain(null);
                query.setAllowedDomains(List.of("__NONE__"));
                return query;
            }
            query.setDomain(domain);
        }
        Scope scope = current();
        if (scope.ownActorOnly()) {
            query.setOperator(null);
            query.setOperatorExact(scope.actor());
        } else if (scope.allowedDomains() != null) {
            query.setAllowedDomains(scope.allowedDomains());
        }
        if (StringUtils.hasText(query.getDomain()) && scope.allowedDomains() != null
                && !scope.allowedDomains().contains(query.getDomain().trim().toUpperCase(Locale.ROOT))) {
            query.setAllowedDomains(List.of("__NONE__"));
        }
        return query;
    }

    public boolean hasUnrestrictedRead() {
        Scope scope = current();
        return scope.allowedDomains() == null && !scope.ownActorOnly();
    }

    /**
     * Decision and replay authorization uses the same row scope as the A2 queue.
     * Missing, malformed and cross-domain IDs intentionally collapse to a denial.
     */
    public boolean canAccessOperation(String operationId) {
        if (!StringUtils.hasText(operationId)) {
            return false;
        }
        String normalized = operationId.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() > 64 || !OPERATION_ID.matcher(normalized).matches()) {
            return false;
        }
        AuditOperationTicketEntity ticket = ticketMapper.selectActiveByOperationId(normalized);
        return canAccessTicket(ticket);
    }

    /** Rechecks the row selected under the decision transaction's lock. */
    public boolean canAccessTicket(AuditOperationTicketEntity ticket) {
        Scope scope = current();
        Long makerId = ticket != null && scope.ownActorOnly()
                ? ticketMapper.selectMakerAudits(List.of(ticket.getOperationId())).stream().findFirst()
                        .map(AuditOperationTicketMapper.MakerAudit::makerId).orElse(null)
                : null;
        return canAccessTicket(ticket, makerId, scope);
    }

    boolean canAccessTicket(AuditOperationTicketEntity ticket, Long makerId) {
        return canAccessTicket(ticket, makerId, current());
    }

    private boolean canAccessTicket(AuditOperationTicketEntity ticket, Long makerId, Scope scope) {
        if (ticket == null || !StringUtils.hasText(ticket.getSourceDomain())) {
            return false;
        }
        char first = Character.toUpperCase(ticket.getSourceDomain().trim().charAt(0));
        if (first < 'A' || first > 'M') {
            return false;
        }
        return scope.ownActorOnly()
                ? makerId != null && makerId.equals(A2ReplayContext.authenticatedAdminId())
                : scope.canSee(String.valueOf(first), ticket.getOperatorName());
    }

    private List<String> authorityDomains() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getAuthorities() == null) {
            return List.of("__NONE__");
        }
        return authorityDomains(authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList());
    }

    private List<String> authorityDomains(java.util.Collection<String> authorities) {
        Set<String> allowed = new LinkedHashSet<>();
        for (String authority : authorities) {
            if (!StringUtils.hasText(authority)) {
                continue;
            }
            Matcher matcher = BUSINESS_AUTHORITY.matcher(authority.trim());
            if (!matcher.find()) {
                continue;
            }
            String domain = matcher.group(2).toUpperCase(Locale.ROOT);
            String expectedPrefix = DOMAIN_AUTHORITY_PREFIX.get(domain);
            if (!matcher.group(1).equalsIgnoreCase(expectedPrefix)) {
                continue;
            }
            // A2 permissions authorize the audit surface itself, not every A-domain business row.
            if ("A".equals(domain) && "2".equals(matcher.group(3))) {
                continue;
            }
            allowed.add(domain);
        }
        if (allowed.isEmpty()) {
            return List.of("__NONE__");
        }
        return DOMAIN_ORDER.stream().filter(allowed::contains).toList();
    }

    public record Scope(String roleCode, String actor, List<String> allowedDomains, boolean ownActorOnly) {
        public boolean canSee(String domain, String operator) {
            if (ownActorOnly) {
                return StringUtils.hasText(actor) && actor.equalsIgnoreCase(operator == null ? "" : operator);
            }
            return allowedDomains == null || (StringUtils.hasText(domain)
                    && allowedDomains.contains(domain.toUpperCase(Locale.ROOT)));
        }
    }
}
