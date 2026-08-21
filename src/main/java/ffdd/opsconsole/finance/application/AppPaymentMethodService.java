package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.mapper.AppPaymentMethodMapper;
import ffdd.opsconsole.finance.mapper.AppPaymentMethodMapper.CardRow;
import ffdd.opsconsole.user.application.OpsUserPaymentMethodService;
import ffdd.opsconsole.user.dto.UserPaymentMethodCommandRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Binds only an opaque provider token; the browser never persists card facts in remote mode. */
@Service
@RequiredArgsConstructor
public class AppPaymentMethodService {
    private static final Set<String> BRANDS = Set.of("visa", "mastercard", "amex", "unionpay", "unknown");
    private final AppPaymentMethodMapper mapper;
    private final AdminIdempotencyService idempotency;
    private final PaymentMethodProviderProperties providerProperties;
    private final PaymentMethodSandboxProfileGuard profileGuard;
    private final OpsUserPaymentMethodService userPaymentMethods;

    @Transactional(readOnly = true)
    public ApiResult<Map<String, Object>> list(Long userId) {
        Scope scope = scope();
        requireUser(userId, scope);
        return ApiResult.ok(linked("cards", mapper.listScoped(userId, scope.sourceEnvironment(), scope.runId()).stream()
                .map(this::view).toList(), "serverCanonical", true, "sourceEnvironment", scope.sourceEnvironment(),
                "runId", scope.runId(), "source", scope.source(), "sandbox", scope.sandbox()));
    }

    public ApiResult<Map<String, Object>> bind(Long userId, BindRequest request, String idempotencyKey) {
        Scope scope = scope();
        requireUser(userId, scope);
        if (request == null) throw new BizException(422, "PAYMENT_METHOD_BIND_REQUEST_REQUIRED");
        String token = required(request.providerToken(), "PAYMENT_METHOD_PROVIDER_TOKEN_INVALID", "[A-Za-z0-9_-]{16,96}");
        String source = required(request.source(), "PAYMENT_METHOD_TOKEN_SOURCE_INVALID", "[a-z_]{4,24}");
        requireVerifiedTokenBoundary(token, source);
        String brand = required(request.brand(), "PAYMENT_METHOD_BRAND_INVALID", "[a-z]+") .toLowerCase(Locale.ROOT);
        if (!BRANDS.contains(brand)) throw new BizException(422, "PAYMENT_METHOD_BRAND_INVALID");
        String last4 = required(request.last4(), "PAYMENT_METHOD_LAST4_INVALID", "\\d{4}");
        String holder = required(request.holder(), "PAYMENT_METHOD_HOLDER_INVALID", ".{2,80}").toUpperCase(Locale.ROOT);
        String hash = sha256(scope.sourceEnvironment() + "|" + scope.runId() + "|" + userId + "|" + token + "|" + source + "|" + brand + "|" + last4 + "|" + holder + "|" + request.makeDefault());
        return idempotency.execute("APP_PAYMENT_METHOD_BIND:" + scope.sourceEnvironment() + ":" + scope.runId() + ":" + userId, idempotencyKey, hash, ApiResult.class,
                () -> bindOnce(userId, token, brand, last4, holder, request.makeDefault(), scope));
    }

    public ApiResult<Map<String, Object>> unbind(Long userId, Long methodId, Long expectedVersion, String key) {
        Scope scope = scope();
        requireUser(userId, scope);
        // The legacy administrative revocation mapper has no run dimension.
        // Refuse the sandbox operation until it can be routed through a scoped
        // revoke command rather than risking a card from another fixture run.
        if (scope.sandbox()) throw new BizException(503, "PAYMENT_METHOD_SANDBOX_UNAVAILABLE");
        Map<String, Object> result = userPaymentMethods.unbind(userId, methodId, key,
                new UserPaymentMethodCommandRequest("user requested payment method unbind", expectedVersion, "app"));
        return ApiResult.ok(linked("serverCanonical", true, "receipt", "CARD_UNBOUND", "result", result));
    }

    @Transactional
    public ApiResult<Map<String, Object>> setDefault(Long userId, Long methodId, Long expectedVersion, String key) {
        Scope scope = scope();
        requireUser(userId, scope);
        if (expectedVersion == null) throw new BizException(422, "PAYMENT_METHOD_EXPECTED_VERSION_REQUIRED");
        if (key == null || key.isBlank()) throw new BizException(422, "IDEMPOTENCY_KEY_REQUIRED");
        return idempotency.execute("APP_PAYMENT_METHOD_DEFAULT:" + scope.sourceEnvironment() + ":" + scope.runId() + ":" + userId, key,
                sha256(scope.sourceEnvironment() + "|" + scope.runId() + "|" + userId + "|" + methodId + "|" + expectedVersion), ApiResult.class,
                () -> setDefaultOnce(userId, methodId, expectedVersion, scope));
    }

    @Transactional
    ApiResult<Map<String, Object>> setDefaultOnce(Long userId, Long methodId, Long expectedVersion, Scope scope) {
        String env = scope.sourceEnvironment();
        // Lock the account before reading the target. This serializes the
        // clear-then-CAS sequence even when no card is currently default.
        if (mapper.lockActiveUser(userId) == null) throw new BizException(401, "USER_AUTH_REQUIRED");
        CardRow target = mapper.findActiveByIdScoped(userId, methodId, env, scope.runId());
        if (target == null) throw new BizException(404, "PAYMENT_METHOD_NOT_FOUND");
        if (target.version() == null || !expectedVersion.equals(target.version())) {
            throw new BizException(409, "PAYMENT_METHOD_VERSION_CONFLICT");
        }
        if (target.isDefault()) return ApiResult.ok(linked("serverCanonical", true, "receipt", "CARD_DEFAULT_SET", "card", view(target)));
        if (mapper.defaultTrialGuardScoped(userId, env, scope.runId())) throw new BizException(409, "PAYMENT_METHOD_TRIAL_GUARDED");
        mapper.clearDefaultScoped(userId, env, scope.runId());
        if (mapper.setDefaultScoped(userId, methodId, expectedVersion, env, scope.runId()) != 1) {
            throw new BizException(409, "PAYMENT_METHOD_VERSION_CONFLICT");
        }
        CardRow saved = mapper.findActiveByIdScoped(userId, methodId, env, scope.runId());
        if (saved == null) throw new BizException(409, "PAYMENT_METHOD_DEFAULT_RECEIPT_MISSING");
        return ApiResult.ok(linked("serverCanonical", true, "receipt", "CARD_DEFAULT_SET", "card", view(saved)));
    }

    @Transactional
    ApiResult<Map<String, Object>> bindOnce(Long userId, String token, String brand, String last4, String holder,
                                            boolean makeDefault, Scope scope) {
        String sourceEnvironment = scope.sourceEnvironment();
        String runId = scope.runId();
        mapper.lockActiveUser(userId);
        CardRow existing = mapper.findActiveByTokenScoped(userId, token, sourceEnvironment, runId);
        if (existing != null) return bindReceipt(existing);
        boolean first = mapper.listScoped(userId, sourceEnvironment, runId).isEmpty();
        boolean isDefault = makeDefault || first;
        if (isDefault) mapper.clearDefaultScoped(userId, sourceEnvironment, runId);
        CardRow historical = mapper.findByTokenScoped(userId, token, sourceEnvironment, runId);
        if (historical != null) {
            if (mapper.reactivateScoped(userId, token, brand, last4, holder, isDefault, sourceEnvironment, runId) != 1) {
                throw new BizException(409, "PAYMENT_METHOD_REBIND_CONFLICT");
            }
            CardRow reactivated = mapper.findActiveByTokenScoped(userId, token, sourceEnvironment, runId);
            if (reactivated == null) throw new BizException(409, "PAYMENT_METHOD_BIND_RECEIPT_MISSING");
            return bindReceipt(reactivated);
        }
        Long tokenOwner = mapper.tokenOwnerIncludingDeletedScoped(token, sourceEnvironment, runId);
        if (tokenOwner != null) {
            throw new BizException(409, tokenOwner.equals(userId)
                    ? "PAYMENT_METHOD_TOKEN_RETIRED" : "PAYMENT_METHOD_TOKEN_OWNERSHIP_CONFLICT");
        }
        CardRow row = new CardRow(null, userId, token, brand, last4, holder, isDefault, null, sourceEnvironment, runId, 0L);
        if (mapper.insert(row) != 1) {
            CardRow concurrent = mapper.findActiveByTokenScoped(userId, token, sourceEnvironment, runId);
            if (concurrent != null) return bindReceipt(concurrent);
            throw new BizException(409, "PAYMENT_METHOD_BIND_CONFLICT");
        }
        CardRow saved = mapper.findActiveByTokenScoped(userId, token, sourceEnvironment, runId);
        if (saved == null || saved.id() == null) throw new BizException(409, "PAYMENT_METHOD_BIND_RECEIPT_MISSING");
        return bindReceipt(saved);
    }

    private void requireVerifiedTokenBoundary(String token, String source) {
        PaymentMethodProviderProperties.Mode mode = providerProperties.getMode();
        if (mode == PaymentMethodProviderProperties.Mode.LOCAL_SANDBOX) {
            if (!"mock".equals(source) || !token.matches("tok_[0-9a-f]{24}")) {
                throw new BizException(422, "PAYMENT_METHOD_SANDBOX_TOKEN_INVALID");
            }
            return;
        }
        if ("mock".equals(source) || token.startsWith("tok_") || token.startsWith("sbx_")) {
            throw new BizException(422, "PAYMENT_METHOD_PROVIDER_VERIFICATION_REQUIRED");
        }
        if (mode == PaymentMethodProviderProperties.Mode.PROVIDER) {
            throw new BizException(503, "PAYMENT_METHOD_PROVIDER_VERIFIER_UNAVAILABLE");
        }
        throw new BizException(503, "PAYMENT_METHOD_PROVIDER_DISABLED");
    }

    private ApiResult<Map<String, Object>> bindReceipt(CardRow row) {
        boolean sandbox = sandbox(row);
        return ApiResult.ok(linked(
                "receipt", "CARD_BOUND", "card", view(row), "serverCanonical", true,
                "source", sandbox ? "mock" : "provider", "sandbox", sandbox,
                "providerCanonical", !sandbox, "sourceEnvironment", row.sourceEnvironment(),
                "runId", row.runId() == null ? "" : row.runId()));
    }

    private void requireUser(Long userId, Scope scope) {
        if (userId == null || mapper.activeUser(userId) == null) throw new BizException(401, "USER_AUTH_REQUIRED");
        Integer sandbox = mapper.userSandbox(userId);
        if (sandbox == null) throw new BizException(403, "PAYMENT_METHOD_USER_REQUIRED");
        boolean sandboxSource = scope.sandbox();
        if (sandboxSource && sandbox != 1) throw new BizException(403, "PAYMENT_METHOD_SANDBOX_USER_REQUIRED");
        if (!sandboxSource && sandbox != 0) throw new BizException(403, "PAYMENT_METHOD_PRODUCTION_USER_REQUIRED");
    }
    private String required(String value, String error, String pattern) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.matches(pattern)) throw new BizException(422, error);
        return normalized;
    }
    private boolean sandbox(CardRow row) {
        return "SANDBOX".equals(row.sourceEnvironment());
    }

    private Scope scope() {
        if (profileGuard.isLocalSandboxEnabled()) {
            return new Scope("mock", "SANDBOX", profileGuard.requireRunId(), true);
        }
        if (profileGuard.isStrictIsolatedProfile()) {
            throw new BizException(503, "PAYMENT_METHOD_SANDBOX_UNAVAILABLE");
        }
        if (!profileGuard.isStrictProductionProfile()) {
            throw new BizException(503, "PAYMENT_METHOD_PROFILE_INVALID");
        }
        return new Scope("provider", "PRODUCTION", "", false);
    }
    private Map<String, Object> view(CardRow row) {
        boolean sandbox = sandbox(row);
        return linked("id", row.id(), "tokenId", String.valueOf(row.id()), "version", row.version() == null ? 0L : row.version(), "brand", row.brand(),
                "last4", row.last4(), "holder", row.holder(), "status", "BOUND",
                "isDefault", row.isDefault(), "boundAt", row.createdAt() == null ? null : row.createdAt().toString(),
                "source", sandbox ? "mock" : "provider", "sandbox", sandbox,
                "providerCanonical", !sandbox, "sourceEnvironment", row.sourceEnvironment(),
                "runId", row.runId() == null ? "" : row.runId());
    }
    private Map<String, Object> linked(Object... values) { Map<String, Object> map = new LinkedHashMap<>(); for (int i = 0; i < values.length; i += 2) map.put(String.valueOf(values[i]), values[i + 1]); return map; }
    private String sha256(String value) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception ex) { throw new IllegalStateException(ex); } }
    public record BindRequest(String providerToken, String source, String brand, String last4, String holder, boolean makeDefault) { }
    private record Scope(String source, String sourceEnvironment, String runId, boolean sandbox) { }
}
