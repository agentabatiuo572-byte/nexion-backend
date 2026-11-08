package ffdd.opsconsole.shared.idempotency;

import java.util.Map;
import java.util.TreeMap;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

/** Server-derived command identity. Never accepts actor, route or target from a recovery caller. */
public record AdminCommandOrigin(long actorId, String method, String path, String target, String readAuthority) {
    public static AdminCommandOrigin current(String scope) {
        return current(scope, readAuthority(scope), null);
    }

    public static AdminCommandOrigin current(String scope, String readAuthority, String boundTarget) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (!supported(scope) || readAuthority == null || auth == null || !auth.isAuthenticated()
                || !(auth.getDetails() instanceof Map<?, ?> details) || !"ADMIN".equals(details.get("subjectType"))
                || !(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) return null;
        long actor;
        try { actor = Long.parseLong(auth.getName()); }
        catch (NumberFormatException invalid) { return null; }
        var request = attributes.getRequest();
        if (actor < 1 || !request.getRequestURI().startsWith("/api/admin/") || request.getRequestURI().length() > 512) return null;
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String target = boundTarget != null ? boundTarget : variables instanceof Map<?, ?> map && !map.isEmpty()
                ? new TreeMap<>(map).toString() : null;
        if (target == null && (scope.startsWith("GROWTH:H2:") || scope.startsWith("K1_WHITELIST_")
                || scope.startsWith("D3_SENSITIVE_EXPORT_") || scope.startsWith("C2_ACCOUNT_LIST_UPSERT:")
                || "D3_FORECAST_CONFIG_UPDATE".equals(scope) || "I5_DISCLOSURE_GATE_UPDATE".equals(scope))) target = scope;
        // A collection route is not an object identity. It must never lock unrelated objects.
        if (target != null && target.length() > 512) target = null;
        return new AdminCommandOrigin(actor, request.getMethod(), request.getRequestURI(), target, readAuthority);
    }

    public static String readAuthority(String scope) {
        if (scope == null) return null;
        if (scope.startsWith("D2")) return "finance_d2_read";
        if (scope.startsWith("D3")) return "finance_d3_read";
        if (scope.startsWith("E4")) return "device_e4_read";
        if (scope.startsWith("H2") || scope.startsWith("GROWTH:H2:")) return "growth_h2_read";
        if (scope.startsWith("K1")) return "risk_k1_read";
        if (scope.startsWith("J1")) return "emergency_j1_read";
        if (scope.startsWith("I5")) return "content_i5_read";
        if (scope.matches("C[1-6](?:[_:].*)?")) return "user_c1hub_read";
        return null; // A2 and L1 require the typed business caller's binding.
    }

    public static boolean supported(String scope) {
        return scope != null && (scope.matches("(?:D2|D3|E4|H2|K1|J1|I5|C[1-6])(?:[_:].*)?")
                || scope.startsWith("GROWTH:H2:")
                || "A2_COMMAND".equals(scope) || "L_BI_REPORT_CREATE".equals(scope) || scope.startsWith("L_BI_REPORT_ACTION"));
    }

    /** These commands write transactional local configuration, with no provider/remote business call. */
    public static boolean localConfiguration(String scope) {
        return java.util.Set.of("I5_JURISDICTION_CREATE", "I5_JURISDICTION_UPDATE", "I5_DISCLOSURE_DRAFT_SAVE",
                "I5_DISCLOSURE_DRAFT_DELETE", "I5_DISCLOSURE_MATRIX_CONFIGURE", "I5_DISCLOSURE_MATRIX_ARCHIVE",
                "I5_DISCLOSURE_GATE_UPDATE").contains(scope);
    }
}
