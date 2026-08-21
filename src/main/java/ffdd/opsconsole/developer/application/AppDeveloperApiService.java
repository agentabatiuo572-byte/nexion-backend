package ffdd.opsconsole.developer.application;

import ffdd.opsconsole.developer.mapper.AppDeveloperAccessMapper;
import ffdd.opsconsole.developer.mapper.AppDeveloperApiKeyMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AppDeveloperApiService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final AppDeveloperApiKeyMapper keys;
    private final AppDeveloperAccessMapper access;
    private final Environment environment;

    @Transactional
    public ApiResult<Map<String, Object>> createKey(Long userId, String name, String idempotencyKey) {
        try {
            DeveloperAccountGuard.Scope scope = guard().requireApproved(userId, true);
            String normalizedName = normalized(name);
            String key = normalized(idempotencyKey);
            if (normalizedName == null || normalizedName.length() > 100 || key == null || key.length() > 128) {
                return ApiResult.fail(422, "DEVELOPER_API_KEY_INVALID");
            }
            String requestHash = hash(normalizedName);
            var existing = keys.byIdempotency(userId, scope.sourceEnvironment(), scope.runId(), key);
            if (existing != null) {
                return existing.requestHash().equals(requestHash) ? ApiResult.ok(view(existing, null))
                        : ApiResult.fail(409, "IDEMPOTENCY_PAYLOAD_CONFLICT");
            }
            String secret = generateSecret();
            String keyId = UUID.randomUUID().toString();
            String prefix = secret.substring(0, Math.min(16, secret.length()));
            String last4 = secret.substring(secret.length() - 4);
            if (keys.insertKey(new AppDeveloperApiKeyMapper.KeyWrite(keyId, userId, key, requestHash, normalizedName,
                    hash(secret), prefix, last4, scope.sourceEnvironment(), scope.runId())) != 1) {
                existing = keys.byIdempotency(userId, scope.sourceEnvironment(), scope.runId(), key);
                return existing != null && existing.requestHash().equals(requestHash)
                        ? ApiResult.ok(view(existing, null)) : ApiResult.fail(409, "DEVELOPER_API_KEY_CONFLICT");
            }
            var inserted = keys.byIdempotency(userId, scope.sourceEnvironment(), scope.runId(), key);
            return inserted == null ? ApiResult.fail(503, "DEVELOPER_API_KEY_RESULT_UNKNOWN") : ApiResult.ok(view(inserted, secret));
        } catch (BizException ex) {
            return ApiResult.fail(ex.getCode(), ex.getMessage());
        }
    }

    public ApiResult<List<Map<String, Object>>> listKeys(Long userId) {
        try {
            var scope = guard().requireApproved(userId, false);
            List<Map<String, Object>> out = new ArrayList<>();
            for (var row : keys.list(userId, scope.sourceEnvironment(), scope.runId())) out.add(view(row, null));
            return ApiResult.ok(out);
        } catch (BizException ex) { return ApiResult.fail(ex.getCode(), ex.getMessage()); }
    }

    @Transactional
    public ApiResult<Map<String, Object>> revoke(Long userId, Long id) {
        try {
            var scope = guard().requireApproved(userId, true);
            if (id == null || id <= 0) return ApiResult.fail(422, "DEVELOPER_API_KEY_INVALID");
            var row = keys.byId(id, userId, scope.sourceEnvironment(), scope.runId());
            if (row == null) return ApiResult.fail(404, "DEVELOPER_API_KEY_NOT_FOUND");
            keys.revoke(id, userId, scope.sourceEnvironment(), scope.runId());
            var after = keys.byId(id, userId, scope.sourceEnvironment(), scope.runId());
            return ApiResult.ok(view(after == null ? row : after, null));
        } catch (BizException ex) { return ApiResult.fail(ex.getCode(), ex.getMessage()); }
    }

    /** API-key authentication hook for the future /openapi gateway; it performs no business request. */
    public Map<String, Object> authenticate(String rawSecret) {
        if (rawSecret == null || rawSecret.length() < 16 || rawSecret.length() > 256) {
            throw new BizException(401, "DEVELOPER_API_KEY_INVALID");
        }
        var row = keys.activeByHash(hash(rawSecret));
        if (row == null) throw new BizException(401, "DEVELOPER_API_KEY_INVALID");
        var scope = guard().requireApproved(row.userId(), false);
        if (!scope.sourceEnvironment().equals(row.sourceEnvironment()) || !scope.runId().equals(row.runId())) {
            throw new BizException(401, "DEVELOPER_API_KEY_INVALID");
        }
        keys.touchLastUsed(row.id());
        return Map.of("userId", row.userId(), "keyId", row.keyId(), "sourceEnvironment", row.sourceEnvironment(), "runId", row.runId());
    }

    private DeveloperAccountGuard guard() { return new DeveloperAccountGuard(access, environment); }
    static String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception ex) { throw new IllegalStateException(ex); } }
    private String generateSecret() { byte[] bytes = new byte[24]; RANDOM.nextBytes(bytes); return "sk_live_" + HexFormat.of().formatHex(bytes); }
    private String normalized(String value) { return value == null || value.trim().isEmpty() ? null : value.trim(); }
    private Map<String, Object> view(AppDeveloperApiKeyMapper.KeyRow row, String secret) {
        Map<String, Object> out = new LinkedHashMap<>(); out.put("id", row.id()); out.put("keyId", row.keyId()); out.put("name", row.name()); out.put("prefix", row.prefix()); out.put("last4", row.last4()); out.put("status", row.status()); out.put("source", "server"); out.put("sourceEnvironment", row.sourceEnvironment()); out.put("runId", row.runId()); out.put("createdAt", row.createdAt().atOffset(ZoneOffset.UTC).toInstant().toString());
        if (row.revokedAt() != null) out.put("revokedAt", row.revokedAt().atOffset(ZoneOffset.UTC).toInstant().toString());
        if (secret != null) out.put("secret", secret);
        return out;
    }
}
