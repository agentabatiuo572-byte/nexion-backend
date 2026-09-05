package ffdd.opsconsole.developer.application;

import ffdd.opsconsole.developer.mapper.AppDeveloperAccessMapper;
import ffdd.opsconsole.developer.mapper.AppDeveloperApiKeyMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
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
    private final AdminIdempotencyService idempotency;
    private final AuditLogService audit;

    @Transactional
    public ApiResult<Map<String, Object>> createKey(Long userId, String name, String idempotencyKey) {
        try {
            var scope = guard().requireApproved(userId, true);
            String normalizedName = normalize(name);
            String idem = normalize(idempotencyKey);
            if (normalizedName == null || normalizedName.length() > 100 || idem == null || idem.length() > 128) {
                return ApiResult.fail(422, "DEVELOPER_API_KEY_INVALID");
            }
            String requestHash = hash(normalizedName);
            AtomicReference<String> freshSecret = new AtomicReference<>();
            ApiResult<Map<String, Object>> result = once("APP_DEVELOPER_API_KEY_CREATE:" + userId + ":"
                            + scope.sourceEnvironment() + ":" + scope.runId(), idem, requestHash,
                    () -> createOnce(userId, normalizedName, idem, requestHash, scope, freshSecret));
            // Plaintext is never stored in the idempotency record or database.
            // It is attached only to the successful first in-process response.
            if (result.getCode() == 0 && result.getData() != null && freshSecret.get() != null) {
                result.getData().put("secret", freshSecret.get());
            }
            return result;
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
    public ApiResult<Map<String, Object>> revoke(Long userId, Long id, String idempotencyKey) {
        try {
            var scope = guard().requireApproved(userId, true);
            if (id == null || id <= 0) return ApiResult.fail(422, "DEVELOPER_API_KEY_INVALID");
            String requestHash = hash("REVOKE\n" + id);
            return once("APP_DEVELOPER_API_KEY_REVOKE:" + userId + ":" + id, idempotencyKey, requestHash,
                    () -> revokeOnce(userId, id, scope));
        } catch (BizException ex) { return ApiResult.fail(ex.getCode(), ex.getMessage()); }
    }

    public Map<String, Object> authenticate(String rawSecret) {
        String secret = normalize(rawSecret);
        if (secret == null || !secret.matches("sk_(?:live|test)_[A-Za-z0-9_-]{40,80}")) {
            throw new BizException(401, "DEVELOPER_API_KEY_INVALID");
        }
        var row = keys.activeByHash(hash(secret));
        if (row == null) throw new BizException(401, "DEVELOPER_API_KEY_INVALID");
        var scope = guard().requireApproved(row.userId(), false);
        if (!scope.sourceEnvironment().equals(row.sourceEnvironment()) || !scope.runId().equals(row.runId())) {
            throw new BizException(401, "DEVELOPER_API_KEY_SCOPE_INVALID");
        }
        if (keys.touchLastUsed(row.id()) != 1) {
            throw new BizException(401, "DEVELOPER_API_KEY_INVALID");
        }
        return Map.of("userId", row.userId(), "keyId", row.keyId(),
                "sourceEnvironment", row.sourceEnvironment(), "runId", row.runId());
    }

    private DeveloperAccountGuard guard() { return new DeveloperAccountGuard(access, environment); }
    private ApiResult<Map<String, Object>> createOnce(Long userId, String name, String idempotencyKey,
                                                       String requestHash, DeveloperAccountGuard.Scope scope,
                                                       AtomicReference<String> freshSecret) {
        var existing = keys.byIdempotency(userId, scope.sourceEnvironment(), scope.runId(), idempotencyKey);
        if (existing != null) {
            return requestHash.equals(existing.requestHash())
                    ? ApiResult.ok(view(existing, null))
                    : ApiResult.fail(409, "IDEMPOTENCY_PAYLOAD_CONFLICT");
        }
        String secret = generateSecret(scope.sourceEnvironment());
        String keyId = "dak_" + UUID.randomUUID().toString().replace("-", "");
        String prefix = secret.substring(0, Math.min(16, secret.length()));
        String last4 = secret.substring(secret.length() - 4);
        int inserted = keys.insertKey(new AppDeveloperApiKeyMapper.KeyWrite(keyId, userId, idempotencyKey,
                requestHash, name, hash(secret), prefix, last4, scope.sourceEnvironment(), scope.runId()));
        if (inserted != 1) {
            existing = keys.byIdempotency(userId, scope.sourceEnvironment(), scope.runId(), idempotencyKey);
            if (existing == null) return ApiResult.fail(409, "DEVELOPER_API_KEY_CONFLICT");
            return requestHash.equals(existing.requestHash())
                    ? ApiResult.ok(view(existing, null))
                    : ApiResult.fail(409, "IDEMPOTENCY_PAYLOAD_CONFLICT");
        }
        var created = keys.byIdempotency(userId, scope.sourceEnvironment(), scope.runId(), idempotencyKey);
        if (created == null) return ApiResult.fail(503, "DEVELOPER_API_KEY_RESULT_UNKNOWN");
        recordAudit("DEVELOPER_API_KEY_CREATED", created, userId);
        freshSecret.set(secret);
        return ApiResult.ok(view(created, null));
    }

    private String generateSecret(String sourceEnvironment) {
        byte[] entropy = new byte[32];
        RANDOM.nextBytes(entropy);
        String environmentPrefix = "SANDBOX".equals(sourceEnvironment) ? "test" : "live";
        return "sk_" + environmentPrefix + "_" + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    }

    private String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }
    private ApiResult<Map<String, Object>> revokeOnce(Long userId, Long id, DeveloperAccountGuard.Scope scope) {
        var row = keys.byId(id, userId, scope.sourceEnvironment(), scope.runId());
        if (row == null) return ApiResult.fail(404, "DEVELOPER_API_KEY_NOT_FOUND");
        if (keys.revoke(id, userId, scope.sourceEnvironment(), scope.runId()) != 1 && !"REVOKED".equals(row.status())) {
            return ApiResult.fail(409, "DEVELOPER_API_KEY_CONCURRENT_UPDATE");
        }
        var after = keys.byId(id, userId, scope.sourceEnvironment(), scope.runId());
        var result = after == null ? row : after;
        recordAudit("DEVELOPER_API_KEY_REVOKED", result, userId);
        return ApiResult.ok(view(result, null));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ApiResult<Map<String, Object>> once(String scope, String idempotencyKey, String requestHash,
                                                 Supplier<ApiResult<Map<String, Object>>> action) {
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotency.execute(scope, idempotencyKey, requestHash,
                ApiResult.class, (Supplier) action);
    }

    private void recordAudit(String action, AppDeveloperApiKeyMapper.KeyRow row, Long userId) {
        audit.recordRequired(AuditLogWriteRequest.builder()
                .action(action).resourceType("DEVELOPER_API_KEY").resourceId(String.valueOf(row.id()))
                .userId(userId).actorType("USER").result("SUCCESS").riskLevel("HIGH")
                .detail(Map.of("keyId", row.keyId(), "status", row.status(),
                        "sourceEnvironment", row.sourceEnvironment(), "runId", row.runId()))
                .build());
    }
    static String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception ex) { throw new IllegalStateException(ex); } }
    private Map<String, Object> view(AppDeveloperApiKeyMapper.KeyRow row, String secret) {
        Map<String, Object> out = new LinkedHashMap<>(); out.put("id", row.id()); out.put("keyId", row.keyId()); out.put("name", row.name()); out.put("prefix", row.prefix()); out.put("last4", row.last4()); out.put("status", row.status()); out.put("source", "server"); out.put("sourceEnvironment", row.sourceEnvironment()); out.put("runId", row.runId()); out.put("createdAt", row.createdAt().atOffset(ZoneOffset.UTC).toInstant().toString());
        if (row.revokedAt() != null) out.put("revokedAt", row.revokedAt().atOffset(ZoneOffset.UTC).toInstant().toString());
        if (secret != null) out.put("secret", secret);
        return out;
    }
}
