package ffdd.opsconsole.shared.idempotency;

import java.util.Map;
import java.util.List;
import java.util.TreeMap;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

/** Server-derived command identity. Never accepts actor, route or target from a recovery caller. */
public record AdminCommandOrigin(long actorId, String method, String path, String target, String readAuthority,
                                 List<Target> targets) {
    public record Target(String action, String domain, String type, String id) {}
    public static AdminCommandOrigin current(String scope) {
        return current(scope, readAuthority(scope), null);
    }

    public static AdminCommandOrigin current(String scope, String readAuthority, String boundTarget) {
        return current(scope, readAuthority, boundTarget, List.of());
    }

    public static AdminCommandOrigin current(String scope, String readAuthority, String boundTarget, List<Target> targets) {
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
        String target = boundTarget;
        if (target == null && variables instanceof Map<?, ?> map && !map.isEmpty()) {
            var canonical = new TreeMap<String, String>();
            map.forEach((name, value) -> {
                String id = String.valueOf(value);
                // These catalogue/resource identifiers use case-insensitive keys; numeric C IDs bind as numbers.
                if (scope.startsWith("I5_")) id = id.toUpperCase(java.util.Locale.ROOT);
                if (scope.matches("C[1-6](?:[_:].*)?")) id = id.matches("[+-]?[0-9]+")
                        ? new java.math.BigInteger(id).toString() : id.toUpperCase(java.util.Locale.ROOT);
                canonical.put(String.valueOf(name), id);
            });
            target = canonical.toString();
        }
        if (target == null && (scope.startsWith("GROWTH:H2:") || scope.startsWith("K1_WHITELIST_")
                || scope.startsWith("D3_SENSITIVE_EXPORT_") || scope.startsWith("C2_ACCOUNT_LIST_UPSERT:")
                || "D3_FORECAST_CONFIG_UPDATE".equals(scope) || "I5_DISCLOSURE_GATE_UPDATE".equals(scope))) target = scope;
        if (target == null && h9CommandScope(scope)) {
            target = scope.startsWith("GROWTH:H9:DEVICE_RANK_UPDATE:")
                    ? scope.substring("GROWTH:H9:DEVICE_RANK_UPDATE:".length()).toLowerCase(java.util.Locale.ROOT) : "PUBLIC_STATS";
        }
        if (target == null && a3PlatformParamScope(scope)) {
            target = scope.substring("A3_PLATFORM_PARAM:".length());
        }
        // A collection route is not an object identity. It must never lock unrelated objects.
        if (target != null && target.length() > 512) target = null;
        if (targets.isEmpty() && target != null) targets = List.of(new Target(
                scope.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9_.:-]", "_"), readAuthority, "business_target", target));
        return new AdminCommandOrigin(actor, request.getMethod(), request.getRequestURI(), target, readAuthority, targets);
    }

    public static String readAuthority(String scope) {
        if (scope == null) return null;
        if (scope.startsWith("D2")) return "finance_d2_read";
        if (scope.startsWith("E2_POOL_")) return "device_e2_read";
        if (scope.startsWith("E5_DEVICE_")) return "device_e5_read";
        if (scope.startsWith("E6_PHONE_")) return "device_e6_read";
        if (scope.startsWith("D3")) return "finance_d3_read";
        if ("D5_WITHDRAWAL_LIMITS_UPDATE".equals(scope) || "D5_WITHDRAWAL_LIMITS".equals(scope)) return "finance_d5_read";
        if ("D6_FX_QUOTE_UPDATE".equals(scope) || "D6_FX_QUOTE".equals(scope)) return "finance_d6_read";
        if ("FINANCE:D7:CONFIG_UPDATE".equals(scope) || "D7_PAYOUT_VND_CONFIG".equals(scope)) return "finance_d7_read";
        if (scope.startsWith("E4")) return "device_e4_read";
        if (scope.startsWith("H2") || scope.startsWith("GROWTH:H2:")) return "growth_h2_read";
        if (scope.startsWith("GROWTH:H4:WHEEL_TIER_RETIRE:")) return "growth_h4_read";
        if (scope.startsWith("K1")) return "risk_k1_read";
        if ("J3_ALERT_CONFIG".equals(scope)) return "emergency_j3_read";
        if (scope.startsWith("I3_NOTIFICATION_CAMPAIGN_")) return "content_i3_read";
        if (scope.startsWith("I6_I18N_")) return "content_i6_read";
        if (i7CourseWrite(scope)) return "content_i7_read";
        if (scope.startsWith("M4_SUPPORT_FAQ_")) return "service_m4_read";
        if (j2GeoWrite(scope)) return "emergency_j2_read";
        if (scope.startsWith("I1_COPY_")) return "content_i1_read";
        if (scope.startsWith("I2_NOVA_")) return "content_i2_read";
        if (j4PlaybookWrite(scope)) return "emergency_j4_read";
        if (scope.startsWith("J1")) return "emergency_j1_read";
        if (scope.startsWith("I5")) return "content_i5_read";
        if ("A4_EVENT_RETENTION_RUN".equals(scope)) return "platform_a4_read";
        if (java.util.Set.of("I4_DISCLOSURE_PUBLISH", "I4_GATE_ADJUST").contains(scope)) return "content_i5_read";
        if (i4TrustWrite(scope)) return "content_i4_read";
        if (h9CommandScope(scope)) return "growth_h9_read";
        if (a3PlatformParamScope(scope)) return null; // Bound per canonical key by executeBound.
        if (userDetailScope(scope)) return "user_c1hub_read";
        if (e3TradeinScope(scope)) return null; // Bound per entry (E3 or C2) by executeBound.
        if (scope.startsWith("F1_VRANK_")) return "network_f1_read";
        if ("F4_AMBASSADOR_POLICY_UPDATE".equals(scope) || scope.startsWith("F4_MATERIAL_REVIEW:")) return "network_f4_read";
        if (java.util.Set.of("F5_COMMISSION_REVERSE", "F5_COMMISSION_REISSUE", "F5_COMMISSION_SUSPENSION",
                "F5_COMMISSION_ANOMALY_CONFIG").contains(scope)) return "network_f5_read";
        if ("F_TEAM_CONFIG_UPDATE".equals(scope)) return null; // Bound per business key by executeBound.
        if (scope.matches("C[1-6](?:[_:].*)?")) return "user_c1hub_read";
        return null; // A2 and L1 require the typed business caller's binding.
    }

    public static boolean supported(String scope) {
        return scope != null && (scope.matches("(?:D2|D3|E4|H2|K1|J1|I5|C[1-6])(?:[_:].*)?")
                || scope.startsWith("E2_POOL_") || scope.startsWith("E5_DEVICE_") || scope.startsWith("E6_PHONE_")
                || scope.startsWith("GROWTH:H2:")
                || scope.startsWith("GROWTH:H4:WHEEL_TIER_RETIRE:")
                || "J3_ALERT_CONFIG".equals(scope)
                || "A4_EVENT_RETENTION_RUN".equals(scope)
                || scope.startsWith("I3_NOTIFICATION_CAMPAIGN_") || scope.startsWith("I6_I18N_")
                || i7CourseWrite(scope)
                || scope.startsWith("M4_SUPPORT_FAQ_")
                || i4TrustWrite(scope)
                || j2GeoWrite(scope)
                || "D5_WITHDRAWAL_LIMITS_UPDATE".equals(scope) || "D5_WITHDRAWAL_LIMITS".equals(scope)
                || "D6_FX_QUOTE_UPDATE".equals(scope) || "D6_FX_QUOTE".equals(scope)
                || "FINANCE:D7:CONFIG_UPDATE".equals(scope) || "D7_PAYOUT_VND_CONFIG".equals(scope)
                || scope.startsWith("I1_COPY_") || scope.startsWith("I2_NOVA_")
                || j4PlaybookWrite(scope)
                || h9CommandScope(scope)
                || a3PlatformParamScope(scope)
                || userDetailScope(scope)
                || e3TradeinScope(scope)
                || fCommandScope(scope)
                || "A2_COMMAND".equals(scope) || "L_BI_REPORT_CREATE".equals(scope) || scope.startsWith("L_BI_REPORT_ACTION"));
    }

    /** Only the four J4 writes that stay inside the transaction: config records, a drill record, a cancel flag. */
    private static boolean j4LocalRecord(String scope) {
        if (scope == null) return false;
        return "J4_PLAYBOOK_CREATE".equals(scope) || scope.startsWith("J4_PLAYBOOK_UPDATE:")
                || scope.startsWith("J4_PLAYBOOK_DRILL:") || scope.startsWith("J4_PLAYBOOK_CANCEL:");
    }

    /** The seven J4 playbook writes that carry an idempotency scope. */
    private static boolean j4PlaybookWrite(String scope) {
        if (scope == null) return false;
        return "J4_PLAYBOOK_CREATE".equals(scope) || scope.startsWith("J4_PLAYBOOK_UPDATE:")
                || scope.startsWith("J4_PLAYBOOK_DRILL:") || scope.startsWith("J4_PLAYBOOK_EXECUTE:")
                || scope.startsWith("J4_PLAYBOOK_CANCEL:") || scope.startsWith("J4_PLAYBOOK_RESUME:")
                || scope.startsWith("J4_PLAYBOOK_ROLLBACK:");
    }

    private static boolean fCommandScope(String scope) {
        return scope != null && (scope.startsWith("F1_VRANK_")
                || "F_TEAM_CONFIG_UPDATE".equals(scope)
                || "F4_AMBASSADOR_POLICY_UPDATE".equals(scope)
                || scope.startsWith("F4_MATERIAL_REVIEW:")
                || java.util.Set.of("F5_COMMISSION_REVERSE", "F5_COMMISSION_REISSUE",
                "F5_COMMISSION_SUSPENSION", "F5_COMMISSION_ANOMALY_CONFIG").contains(scope));
    }

    /** Existing A3 platform and K1 registration params: one scope with the exact canonical key suffix. */
    public static boolean a3PlatformParamScope(String scope) {
        if (scope == null || scope.length() > 96 || !scope.startsWith("A3_PLATFORM_PARAM:")) return false;
        String key = scope.substring("A3_PLATFORM_PARAM:".length());
        return key.matches("[A-Za-z0-9_.-]{1,80}");
    }

    /** Existing user-detail payment/nickname commands; the two payment scopes carry their environment. */
    public static boolean userDetailScope(String scope) {
        return scope != null && (scope.matches("USER_PAYMENT_METHOD_(?:UNBIND|REBIND_NOTICE):(?:PRODUCTION|SANDBOX)")
                || "USER_NICKNAME_RESET".equals(scope));
    }

    /** Only the two existing E3 trade-in direct-write scopes; not the other E3 actions. */
    public static boolean e3TradeinScope(String scope) {
        return "E3_TRADEIN_RECYCLE".equals(scope) || "E3_TRADEIN_REPLACE".equals(scope);
    }

    /**
     * The I4 trust-section and trust-document commands. Only the draft commands are local
     * configuration: publish, rollback, archive and the PDF upload change the public trust surface or
     * write stored objects, so a rollback there must stay unknown rather than be called not-executed.
     */
    private static boolean i4TrustWrite(String scope) {
        if (scope == null) return false;
        return scope.startsWith("I4_TRUST_SECTION_") || "I4_TRUST_DOCUMENT".equals(scope);
    }

    private static boolean i4TrustDraftOnly(String scope) {
        return scope != null && scope.startsWith("I4_TRUST_SECTION_DRAFT_");
    }

    /** The two H9 writers: public-stats and the environment-scoped device-rank config. */
    public static boolean h9CommandScope(String scope) {
        return scope != null && ("GROWTH:H9:PUBLIC_STATS_UPDATE:PUBLIC_STATS".equals(scope)
                || scope.matches("GROWTH:H9:DEVICE_RANK_UPDATE:(?i:growth\\.device_rank\\.model|h9\\.dr\\.sb\\.[0-9a-f]{32})"));
    }

    /**
     * The I7 learning-course commands (the course service kept its old I6 class names, but the page
     * authorities are content_i7_*). The reward command stays out: it is approval-replay only and its
     * direct write is refused.
     */
    private static boolean i7CourseWrite(String scope) {
        if (scope == null) return false;
        return scope.startsWith("I7_COURSE_CREATE") || scope.startsWith("I7_COURSE_DRAFT_UPDATE")
                || scope.startsWith("I7_COURSE_DELETE") || scope.startsWith("I7_COURSE_PUBLISH")
                || scope.startsWith("I7_COURSE_ARCHIVE") || scope.startsWith("I7_COURSE_FEATURED")
                || scope.startsWith("I7_COURSE_VERSION_");
    }

    /** Draft and version editing stay local; publish, rollback, archive and featured change the public surface. */
    private static boolean i7CourseDraftOnly(String scope) {
        if (scope == null) return false;
        return scope.startsWith("I7_COURSE_CREATE") || scope.startsWith("I7_COURSE_DRAFT_UPDATE")
                || scope.startsWith("I7_COURSE_DELETE") || scope.startsWith("I7_COURSE_VERSION_CREATE")
                || scope.startsWith("I7_COURSE_VERSION_UPDATE") || scope.startsWith("I7_COURSE_VERSION_DELETE");
    }

    /**
     * M4 support FAQ edits. Only create and update are local: a status change publishes or retires an
     * answer on the public support surface and a delete removes it, so neither is used as
     * not-executed evidence.
     */
    private static boolean m4FaqLocalEdit(String scope) {
        if (scope == null) return false;
        return "M4_SUPPORT_FAQ_CREATE".equals(scope) || scope.startsWith("M4_SUPPORT_FAQ_UPDATE:");
    }

    /** These commands write transactional local configuration, with no provider/remote business call. */
    public static boolean localConfiguration(String scope) {
        return java.util.Set.of("I5_JURISDICTION_CREATE", "I5_JURISDICTION_UPDATE", "I5_DISCLOSURE_DRAFT_SAVE",
                "I5_DISCLOSURE_DRAFT_DELETE", "I5_DISCLOSURE_MATRIX_CONFIGURE", "I5_DISCLOSURE_MATRIX_ARCHIVE",
                "I5_DISCLOSURE_GATE_UPDATE").contains(scope)
                || "J3_ALERT_CONFIG".equals(scope)
                || j2GeoWrite(scope)
                || "D5_WITHDRAWAL_LIMITS_UPDATE".equals(scope) || "D5_WITHDRAWAL_LIMITS".equals(scope)
                || "D6_FX_QUOTE_UPDATE".equals(scope) || "D6_FX_QUOTE".equals(scope)
                || "FINANCE:D7:CONFIG_UPDATE".equals(scope) || "D7_PAYOUT_VND_CONFIG".equals(scope)
                || j4LocalRecord(scope)
                || h9CommandScope(scope)
                || a3PlatformParamScope(scope)
                || fLocalConfiguration(scope)
                || i7CourseDraftOnly(scope)
                || m4FaqLocalEdit(scope)
                || i4TrustDraftOnly(scope)
                || scope != null && (scope.startsWith("E2_POOL_") || scope.startsWith("E5_DEVICE_") || scope.startsWith("E6_PHONE_"));
    }

    private static boolean fLocalConfiguration(String scope) {
        return java.util.Set.of("F1_VRANK_THRESHOLD_UPDATE", "F1_VRANK_REWARD_ADD",
                "F1_VRANK_REWARD_UPDATE", "F1_VRANK_REWARD_REMOVE",
                "F4_AMBASSADOR_POLICY_UPDATE", "F5_COMMISSION_ANOMALY_CONFIG").contains(scope);
    }

    /**
     * The five J2 geo writes: each one only upserts settings/country rows and publishes an outbox
     * event inside the same transaction, so a rolled-back command proves nothing was written and
     * nothing will propagate. Their edge health check reads an in-memory snapshot, not a provider.
     */
    private static boolean j2GeoWrite(String scope) {
        if (scope == null) return false;
        return scope.startsWith("J2_COUNTRY") || scope.startsWith("J2_ENDPOINT")
                || "J2_EDGE_SOURCE".equals(scope) || "J2_EMERGENCY_BLOCK".equals(scope);
    }
}
