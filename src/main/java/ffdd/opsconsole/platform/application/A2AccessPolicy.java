package ffdd.opsconsole.platform.application;

import ffdd.opsconsole.shared.audit.AuditLogQueryRequest;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Server-side A2 row visibility. UI controls are never an authorization boundary. */
@Component
@RequiredArgsConstructor
public class A2AccessPolicy {
    private final AdminOperatorRoleResolver roleResolver;

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
            default -> new Scope(role, actor, List.of("__NONE__"), false);
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
