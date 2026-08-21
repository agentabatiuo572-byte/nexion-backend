package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.dto.PayoutVndSandboxCallbackRequest;
import ffdd.opsconsole.finance.dto.PayoutVndSandboxCreateRequest;
import ffdd.opsconsole.finance.mapper.PayoutVndSandboxMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class PayoutVndSandboxService {
    private final PayoutVndSandboxMapper mapper;
    private final PayoutVndProviderProperties properties;
    private final AdminIdempotencyService idempotency;
    private final AuditLogService audit;
    private final Clock clock;
    private final FundsSandboxRunScope runScope;

    @Transactional(readOnly = true)
    public ApiResult<Map<String, Object>> orders(Long userId) {
        ApiResult<Map<String, Object>> gate = gate();
        if (gate != null) return gate;
        String runId = runScope.requireRunId();
        if (userId == null || mapper.activeUser(userId) == null) return ApiResult.fail(404, "USER_NOT_FOUND");
        List<Map<String, Object>> rows = mapper.orders(runId, userId);
        return ApiResult.ok(Map.of("userId", userId, "runId", runId, "orders", rows, "source", "mock", "sandbox", true));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public ApiResult<Map<String, Object>> create(String key, PayoutVndSandboxCreateRequest request) {
        ApiResult<Map<String, Object>> gate = gate();
        if (gate != null) return gate;
        if (request == null || request.userId() == null || request.amountVnd() == null
                || request.amountVnd().compareTo(BigDecimal.ZERO) <= 0 || !text(request.bankCode(), 2, 20)
                || !text(request.accountNo(), 4, 40) || !text(request.accountName(), 2, 80)
                || !text(request.reason(), 8, 200)) return ApiResult.fail(422, "PAYOUT_VND_SANDBOX_REQUEST_INVALID");
        String runId = runScope.requireRunId();
        String normalizedKey = requiredKey(key);
        String canonical = runId + "|" + request.userId() + "|" + request.amountVnd().stripTrailingZeros().toPlainString()
                + "|" + request.bankCode().trim().toUpperCase(Locale.ROOT) + "|" + request.accountNo().trim()
                + "|" + request.accountName().trim() + "|" + request.reason().trim();
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotency.execute("FINANCE:D7:SANDBOX_CREATE:" + runId,
                normalizedKey, sha(canonical), ApiResult.class, () -> createOnce(runId, normalizedKey, request));
    }

    @Transactional(rollbackFor = Exception.class)
    protected ApiResult<Map<String, Object>> createOnce(String runId, String key, PayoutVndSandboxCreateRequest request) {
        if (mapper.activeUser(request.userId()) == null) return ApiResult.fail(404, "USER_NOT_FOUND");
        String orderNo = "PVN-MOCK-" + sha(runId + "|" + key).substring(0, 20).toUpperCase(Locale.ROOT);
        LocalDateTime now = LocalDateTime.now(clock);
        mapper.insertOrder(orderNo, runId, request.userId(), request.amountVnd(), request.bankCode().trim().toUpperCase(Locale.ROOT),
                mask(request.accountNo()), request.accountName().trim(), key, request.reason().trim(), now);
        String eventId = "EV-" + sha(runId + "|" + orderNo).substring(0, 20).toUpperCase(Locale.ROOT);
        audit("D7_SANDBOX_PAYOUT_CREATED", orderNo, request.userId(), Map.of("source", "mock", "sandbox", true));
        Map<String, Object> row = mapper.order(runId, orderNo);
        row.put("sandboxCallbackEventId", eventId);
        row.put("sandboxCallbackSignature", signature(eventId, orderNo, "COMPLETED"));
        row.put("runId", runId);
        row.put("sandbox", true);
        return ApiResult.ok(row);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public ApiResult<Map<String, Object>> callback(String key, PayoutVndSandboxCallbackRequest request) {
        ApiResult<Map<String, Object>> gate = gate();
        if (gate != null) return gate;
        if (request == null || !text(request.eventId(), 3, 80) || !text(request.orderNo(), 8, 80)
                || !List.of("COMPLETED", "FAILED").contains(upper(request.status())) || !StringUtils.hasText(request.signature())) {
            return ApiResult.fail(422, "PAYOUT_VND_CALLBACK_INVALID");
        }
        String expected = signature(request.eventId().trim(), request.orderNo().trim(), upper(request.status()));
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), request.signature().trim().getBytes(StandardCharsets.US_ASCII))) {
            return ApiResult.fail(401, "PAYOUT_VND_CALLBACK_SIGNATURE_INVALID");
        }
        String runId = runScope.requireRunId();
        String hash = sha(runId + "|" + request.eventId().trim() + "|" + request.orderNo().trim() + "|" + upper(request.status()));
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotency.execute("FINANCE:D7:SANDBOX_CALLBACK:" + runId,
                requiredKey(key), hash, ApiResult.class, () -> callbackOnce(runId, request));
    }

    @Transactional(rollbackFor = Exception.class)
    protected ApiResult<Map<String, Object>> callbackOnce(String runId, PayoutVndSandboxCallbackRequest request) {
        Map<String, Object> before = mapper.order(runId, request.orderNo().trim());
        if (before == null) return ApiResult.fail(404, "PAYOUT_VND_ORDER_NOT_FOUND");
        String status = upper(request.status());
        if (!"PENDING".equals(String.valueOf(before.get("status")))) {
            return ApiResult.fail(409, "PAYOUT_VND_CALLBACK_REPLAY_CONFLICT");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (mapper.complete(runId, request.orderNo().trim(), status, now) != 1
                || mapper.insertLedger(runId, request.eventId().trim(), request.orderNo().trim(), status, now) != 1) {
            throw new BizException(409, "PAYOUT_VND_CALLBACK_CONFLICT");
        }
        Map<String, Object> row = mapper.order(runId, request.orderNo().trim());
        row.put("runId", runId);
        row.put("sandbox", true);
        audit("D7_SANDBOX_PAYOUT_" + status, request.orderNo().trim(), ((Number) row.get("userId")).longValue(),
                Map.of("eventId", request.eventId().trim(), "source", "mock", "sandbox", true));
        return ApiResult.ok(row);
    }

    private ApiResult<Map<String, Object>> gate() {
        if (properties.getMode() != PayoutVndProviderProperties.Mode.LOCAL_SANDBOX) {
            return ApiResult.fail(503, "PAYOUT_VND_PROVIDER_UNAVAILABLE");
        }
        if (!StringUtils.hasText(properties.getSandboxCallbackSecret())
                || properties.getSandboxCallbackSecret().length() < 24) {
            return ApiResult.fail(503, "PAYOUT_VND_SANDBOX_SECRET_INVALID");
        }
        return null;
    }

    private String signature(String eventId, String orderNo, String status) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getSandboxCallbackSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((eventId + "|" + orderNo + "|" + status).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw new IllegalStateException("PAYOUT_VND_SANDBOX_HMAC_UNAVAILABLE", ex); }
    }

    private String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException("SHA256_UNAVAILABLE", ex); }
    }
    private String requiredKey(String value) { if (!text(value, 8, 128)) throw new BizException(422, "IDEMPOTENCY_KEY_REQUIRED"); return value.trim(); }
    private boolean text(String value, int min, int max) { return StringUtils.hasText(value) && value.trim().length() >= min && value.trim().length() <= max; }
    private String upper(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private String mask(String value) { String v = value.trim(); return "*".repeat(Math.max(4, v.length() - 4)) + v.substring(v.length() - 4); }
    private void audit(String action, String orderNo, Long userId, Map<String, Object> detail) {
        audit.recordRequired(AuditLogWriteRequest.builder().action(action).resourceType("PAYOUT_VND_SANDBOX")
                .resourceId(orderNo).bizNo(orderNo).userId(userId).actorType("ADMIN")
                .actorUsername(AdminActorResolver.resolve("system")).riskLevel("HIGH").result("SUCCESS").detail(detail).build());
    }
}
