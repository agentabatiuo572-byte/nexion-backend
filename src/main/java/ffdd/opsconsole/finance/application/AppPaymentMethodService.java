package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.mapper.AppPaymentMethodMapper;
import ffdd.opsconsole.finance.mapper.AppPaymentMethodMapper.CardRow;
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

    @Transactional(readOnly = true)
    public ApiResult<Map<String, Object>> list(Long userId) {
        requireUser(userId);
        String sourceEnvironment = profileGuard.sourceEnvironment();
        return ApiResult.ok(linked("cards", mapper.list(userId, sourceEnvironment).stream().map(this::view).toList(), "serverCanonical", true));
    }

    public ApiResult<Map<String, Object>> bind(Long userId, BindRequest request, String idempotencyKey) {
        requireUser(userId);
        if (request == null) throw new BizException(422, "PAYMENT_METHOD_BIND_REQUEST_REQUIRED");
        String token = required(request.providerToken(), "PAYMENT_METHOD_PROVIDER_TOKEN_INVALID", "[A-Za-z0-9_-]{16,96}");
        String source = required(request.source(), "PAYMENT_METHOD_TOKEN_SOURCE_INVALID", "[a-z_]{4,24}");
        requireVerifiedTokenBoundary(token, source);
        String brand = required(request.brand(), "PAYMENT_METHOD_BRAND_INVALID", "[a-z]+") .toLowerCase(Locale.ROOT);
        if (!BRANDS.contains(brand)) throw new BizException(422, "PAYMENT_METHOD_BRAND_INVALID");
        String last4 = required(request.last4(), "PAYMENT_METHOD_LAST4_INVALID", "\\d{4}");
        String holder = required(request.holder(), "PAYMENT_METHOD_HOLDER_INVALID", ".{2,80}").toUpperCase(Locale.ROOT);
        String hash = sha256(userId + "|" + token + "|" + source + "|" + brand + "|" + last4 + "|" + holder + "|" + request.makeDefault());
        return idempotency.execute("APP_PAYMENT_METHOD_BIND:" + userId, idempotencyKey, hash, ApiResult.class,
                () -> bindOnce(userId, token, brand, last4, holder, request.makeDefault(), profileGuard.sourceEnvironment()));
    }

    @Transactional
    ApiResult<Map<String, Object>> bindOnce(Long userId, String token, String brand, String last4, String holder,
                                            boolean makeDefault, String sourceEnvironment) {
        CardRow existing = mapper.findActiveByToken(userId, token, sourceEnvironment);
        if (existing != null) return bindReceipt(existing);
        boolean first = mapper.list(userId, sourceEnvironment).isEmpty();
        boolean isDefault = makeDefault || first;
        if (isDefault) mapper.clearDefault(userId, sourceEnvironment);
        CardRow historical = mapper.findByToken(userId, token, sourceEnvironment);
        if (historical != null) {
            if (mapper.reactivate(userId, token, brand, last4, holder, isDefault, sourceEnvironment) != 1) {
                throw new BizException(409, "PAYMENT_METHOD_REBIND_CONFLICT");
            }
            CardRow reactivated = mapper.findActiveByToken(userId, token, sourceEnvironment);
            if (reactivated == null) throw new BizException(409, "PAYMENT_METHOD_BIND_RECEIPT_MISSING");
            return bindReceipt(reactivated);
        }
        Long tokenOwner = mapper.tokenOwnerIncludingDeleted(token, sourceEnvironment);
        if (tokenOwner != null) {
            throw new BizException(409, tokenOwner.equals(userId)
                    ? "PAYMENT_METHOD_TOKEN_RETIRED" : "PAYMENT_METHOD_TOKEN_OWNERSHIP_CONFLICT");
        }
        CardRow row = new CardRow(null, userId, token, brand, last4, holder, isDefault, null, sourceEnvironment);
        if (mapper.insert(row) != 1) {
            CardRow concurrent = mapper.findActiveByToken(userId, token, sourceEnvironment);
            if (concurrent != null) return bindReceipt(concurrent);
            throw new BizException(409, "PAYMENT_METHOD_BIND_CONFLICT");
        }
        CardRow saved = mapper.findActiveByToken(userId, token, sourceEnvironment);
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
                "providerCanonical", !sandbox));
    }

    private void requireUser(Long userId) { if (userId == null || mapper.activeUser(userId) == null) throw new BizException(401, "USER_AUTH_REQUIRED"); }
    private String required(String value, String error, String pattern) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.matches(pattern)) throw new BizException(422, error);
        return normalized;
    }
    private boolean sandbox(CardRow row) {
        return "SANDBOX".equals(row.sourceEnvironment());
    }
    private Map<String, Object> view(CardRow row) {
        boolean sandbox = sandbox(row);
        return linked("id", row.id(), "tokenId", String.valueOf(row.id()), "brand", row.brand(),
                "last4", row.last4(), "holder", row.holder(), "status", "BOUND",
                "isDefault", row.isDefault(), "boundAt", row.createdAt() == null ? null : row.createdAt().toString(),
                "source", sandbox ? "mock" : "provider", "sandbox", sandbox,
                "providerCanonical", !sandbox);
    }
    private Map<String, Object> linked(Object... values) { Map<String, Object> map = new LinkedHashMap<>(); for (int i = 0; i < values.length; i += 2) map.put(String.valueOf(values[i]), values[i + 1]); return map; }
    private String sha256(String value) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception ex) { throw new IllegalStateException(ex); } }
    public record BindRequest(String providerToken, String source, String brand, String last4, String holder, boolean makeDefault) { }
}
