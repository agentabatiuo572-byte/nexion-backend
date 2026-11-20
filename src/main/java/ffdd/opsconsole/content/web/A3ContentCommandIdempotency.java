package ffdd.opsconsole.content.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;

/** Binds an A3 content PUT to the authenticated admin and the exact request body. */
final class A3ContentCommandIdempotency {
    private static final ObjectMapper JSON = new ObjectMapper();

    private A3ContentCommandIdempotency() {}

    @SuppressWarnings({"rawtypes", "unchecked"})
    static ApiResult<Map<String, Object>> execute(
            AdminIdempotencyService idempotency, String scopePrefix, String idempotencyKey,
            Object body, Supplier<ApiResult<Map<String, Object>>> action) {
        Long actor = currentAdminId();
        if (actor == null) return ApiResult.fail(403, "ADMIN_AUTH_REQUIRED");
        String bodyJson = writeJson(body);
        String hash = sha256(actor + "\0" + bodyJson);
        // Legacy PC calls did not send a key. A content-derived key keeps the
        // existing page retry-safe without changing its request shape; an
        // explicit Idempotency-Key still wins for command-level identity.
        String key = StringUtils.hasText(idempotencyKey) ? idempotencyKey.trim() : "auto:" + hash;
        String scope = scopePrefix + ":" + actor;
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotency.execute(
                scope, key, hash, ApiResult.class, (Supplier) action);
    }

    private static Long currentAdminId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getDetails() instanceof Map<?, ?> details)
                || !"ADMIN".equals(details.get("subjectType"))) return null;
        try {
            long id = Long.parseLong(authentication.getName());
            return id > 0 ? id : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String writeJson(Object body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("A3_CONTENT_REQUEST_UNSERIALIZABLE", ex);
        }
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
