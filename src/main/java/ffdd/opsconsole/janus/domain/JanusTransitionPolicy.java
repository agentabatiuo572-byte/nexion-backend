package ffdd.opsconsole.janus.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.util.StringUtils;

public final class JanusTransitionPolicy {
    private static final Set<String> REMOTE_TARGETS = Set.of("default", "backup", "promo");
    private record Spec(JanusRole role, boolean remote, boolean expiry, boolean strong, boolean highRisk, boolean noBatch) {}

    public record Validation(boolean allowed, String code, boolean highRisk, boolean noBatch) {
        static Validation allow(Spec spec) {
            return new Validation(true, null, spec.highRisk(), spec.noBatch());
        }

        static Validation reject(String code) {
            return new Validation(false, code, false, false);
        }
    }

    private static final Map<String, Map<String, Spec>> TRANSITIONS = buildTransitions();

    public Validation validate(String from, String to, JanusRole role, String remoteUrlKey, Long expireAt,
                               String confirmationMode) {
        Spec spec = TRANSITIONS.getOrDefault(code(from), Map.of()).get(code(to));
        if (spec == null) return Validation.reject("ILLEGAL_STATUS_TRANSITION");
        if (role == null || !role.atLeast(spec.role())) return Validation.reject("ROLE_FORBIDDEN");
        if (StringUtils.hasText(remoteUrlKey) && (!remoteUrlKey.equals(remoteUrlKey.trim())
                || !REMOTE_TARGETS.contains(remoteUrlKey))) {
            return Validation.reject("REMOTE_TARGET_INVALID");
        }
        if (spec.remote() && !StringUtils.hasText(remoteUrlKey)) return Validation.reject("REMOTE_TARGET_REQUIRED");
        if (!spec.remote() && StringUtils.hasText(remoteUrlKey)) return Validation.reject("REMOTE_TARGET_NOT_ALLOWED");
        if (spec.expiry() && (expireAt == null || expireAt <= System.currentTimeMillis())) {
            return Validation.reject("EXPIRE_AT_REQUIRED");
        }
        if (spec.strong() && !"strong_single".equalsIgnoreCase(confirmationMode)) {
            return Validation.reject("STRONG_CONFIRMATION_REQUIRED");
        }
        return Validation.allow(spec);
    }

    public Map<String, Map<String, Object>> metadata() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        TRANSITIONS.forEach((from, targets) -> {
            Map<String, Object> targetViews = new LinkedHashMap<>();
            targets.forEach((to, spec) -> targetViews.put(to, Map.of(
                    "requiredRole", spec.role().name().toLowerCase(),
                    "requiresRemoteUrl", spec.remote(),
                    "requiresExpiry", spec.expiry(),
                    "strongConfirmation", spec.strong(),
                    "highRisk", spec.highRisk(),
                    "noBatch", spec.noBatch())));
            result.put(from, targetViews);
        });
        return result;
    }

    private static String code(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private static Map<String, Map<String, Spec>> buildTransitions() {
        Map<String, Map<String, Spec>> all = new LinkedHashMap<>();
        put(all, "NEW", "OBSERVING", op());
        put(all, "NEW", "ENV_FILTERED", op());
        put(all, "NEW", "MANUAL_HOLD", expiry());
        put(all, "NEW", "MANUAL_FORCED", forced(false));
        put(all, "OBSERVING", "RECOMMENDED", op());
        put(all, "OBSERVING", "ENV_FILTERED", op());
        put(all, "OBSERVING", "MANUAL_FORCED", forced(false));
        put(all, "OBSERVING", "BLOCKED", senior());
        put(all, "RECOMMENDED", "MANUAL_FORCED", new Spec(JanusRole.OPERATOR, true, false, false, false, false));
        put(all, "RECOMMENDED", "HIT", op());
        put(all, "RECOMMENDED", "ENV_FILTERED", op());
        put(all, "RECOMMENDED", "MANUAL_HOLD", expiry());
        put(all, "RECOMMENDED", "BLOCKED", senior());
        put(all, "HIT", "ACTIVATED", new Spec(JanusRole.OPERATOR, true, false, false, false, false));
        put(all, "HIT", "ENV_FILTERED", op());
        put(all, "HIT", "MANUAL_HOLD", expiry());
        put(all, "HIT", "RESET", senior());
        put(all, "ACTIVATED", "RESET", high(JanusRole.SENIOR_OPERATOR));
        put(all, "ACTIVATED", "ENV_FILTERED", high(JanusRole.ADMIN));
        put(all, "ACTIVATED", "BLOCKED", high(JanusRole.ADMIN));
        put(all, "ENV_FILTERED", "OBSERVING", op());
        put(all, "ENV_FILTERED", "RECOMMENDED", senior());
        put(all, "ENV_FILTERED", "MANUAL_FORCED", forced(true));
        put(all, "ENV_FILTERED", "BLOCKED", op());
        put(all, "MANUAL_HOLD", "OBSERVING", op());
        put(all, "MANUAL_HOLD", "RECOMMENDED", op());
        put(all, "MANUAL_HOLD", "MANUAL_FORCED", forced(false));
        put(all, "MANUAL_FORCED", "RESET", high(JanusRole.SENIOR_OPERATOR));
        put(all, "MANUAL_FORCED", "BLOCKED", senior());
        put(all, "BLOCKED", "OBSERVING", admin());
        put(all, "BLOCKED", "MANUAL_FORCED", new Spec(JanusRole.ADMIN, true, false, true, true, true));
        put(all, "STALE", "MANUAL_FORCED", forced(false));
        put(all, "RESET", "OBSERVING", op());
        put(all, "ERROR", "OBSERVING", admin());
        return all;
    }

    private static void put(Map<String, Map<String, Spec>> all, String from, String to, Spec spec) {
        all.computeIfAbsent(from, ignored -> new LinkedHashMap<>()).put(to, spec);
    }

    private static Spec op() { return new Spec(JanusRole.OPERATOR, false, false, false, false, false); }
    private static Spec senior() { return new Spec(JanusRole.SENIOR_OPERATOR, false, false, false, false, false); }
    private static Spec admin() { return new Spec(JanusRole.ADMIN, false, false, false, false, false); }
    private static Spec expiry() { return new Spec(JanusRole.OPERATOR, false, true, false, false, false); }
    private static Spec high(JanusRole role) { return new Spec(role, false, false, true, true, false); }
    private static Spec forced(boolean noBatch) { return new Spec(JanusRole.SENIOR_OPERATOR, true, false, true, true, noBatch); }
}
