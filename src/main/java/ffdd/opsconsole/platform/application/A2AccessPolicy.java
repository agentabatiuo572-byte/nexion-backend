package ffdd.opsconsole.platform.application;

import ffdd.opsconsole.platform.infrastructure.AuditOperationTicketEntity;
import ffdd.opsconsole.platform.mapper.AuditOperationTicketMapper;
import ffdd.opsconsole.shared.audit.AuditLogQueryRequest;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
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

    public Scope current() {
        String role = roleResolver.resolveCode();
        String actor = AdminActorResolver.resolve(null);
        return switch (role == null ? "" : role.toUpperCase(Locale.ROOT)) {
            case "SUPER_ADMIN", "AUDITOR" -> new Scope(role, actor, null, false);
            case "FINANCE" -> new Scope(role, actor, List.of("D"), false);
            case "RISK" -> new Scope(role, actor, List.of("C", "K"), false);
            case "GROWTH" -> new Scope(role, actor, List.of("E", "H"), false);
            case "CONTENT" -> new Scope(role, actor, List.of("I"), false);
            case "SUPPORT" -> new Scope(role, actor, null, true);
            default -> new Scope(role, actor, authorityDomains(), false);
        };
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
        if (ticket == null || !StringUtils.hasText(ticket.getSourceDomain())) {
            return false;
        }
        char first = Character.toUpperCase(ticket.getSourceDomain().trim().charAt(0));
        if (first < 'A' || first > 'M') {
            return false;
        }
        return current().canSee(String.valueOf(first), ticket.getOperatorName());
    }

    private List<String> authorityDomains() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getAuthorities() == null) {
            return List.of("__NONE__");
        }
        Set<String> allowed = new LinkedHashSet<>();
        for (GrantedAuthority granted : authentication.getAuthorities()) {
            String authority = granted == null ? null : granted.getAuthority();
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
