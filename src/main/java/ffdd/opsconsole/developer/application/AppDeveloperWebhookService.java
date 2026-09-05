package ffdd.opsconsole.developer.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.developer.mapper.AppDeveloperAccessMapper;
import ffdd.opsconsole.developer.mapper.AppDeveloperWebhookMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AppDeveloperWebhookService {
    public static final String DELIVERY_STATUS = "NOT_DELIVERED";
    private static final int DEFAULT_ACCOUNT_WEBHOOK_LIMIT = 20;
    private static final Set<String> EVENT_ALLOWLIST = Set.of("order.updated", "order.completed", "compute.job.completed",
            "compute.job.failed", "earnings.updated", "billing.invoice.created", "market.updated", "account.updated");
    private final AppDeveloperWebhookMapper webhooks;
    private final AppDeveloperAccessMapper access;
    private final Environment environment;
    private final ffdd.opsconsole.developer.mapper.AppDeveloperWebhookDeliveryMapper deliveries;
    private final AdminIdempotencyService idempotency;
    private final AuditLogService audit;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public ApiResult<Map<String, Object>> create(Long userId, String name, String url, String eventsJson, String idempotencyKey) {
        try {
            var scope = guard().requireApproved(userId, true);
            String n = normalize(name), u = normalize(url), idem = normalize(idempotencyKey);
            List<String> events = events(eventsJson);
            if (n == null || n.length() > 100 || idem == null || idem.length() > 128) return ApiResult.fail(422, "DEVELOPER_WEBHOOK_INVALID");
            String canonicalEvents = objectMapper.writeValueAsString(events);
            String requestHash = AppDeveloperApiService.hash(n + "\n" + u + "\n" + canonicalEvents);
            AtomicReference<String> freshSecret = new AtomicReference<>();
            ApiResult<Map<String, Object>> result = once("APP_DEVELOPER_WEBHOOK_CREATE:" + userId + ":"
                    + scope.sourceEnvironment() + ":" + scope.runId(), idem, requestHash,
                    () -> createOnce(userId, scope, idem, requestHash, n, u, canonicalEvents, freshSecret));
            // The durable idempotency response intentionally excludes credentials. A first response may include
            // the one-time secret; a response-loss replay proves the mutation without persisting plaintext secrets.
            if (result.getCode() == 0 && freshSecret.get() != null && result.getData() != null) {
                result.getData().put("secret", freshSecret.get());
            }
            return result;
        } catch (BizException ex) { return ApiResult.fail(ex.getCode(), ex.getMessage()); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { return ApiResult.fail(422, "DEVELOPER_WEBHOOK_INVALID"); }
    }

    private ApiResult<Map<String, Object>> createOnce(Long userId, DeveloperAccountGuard.Scope scope, String idempotencyKey,
                                                       String requestHash, String name, String url, String eventsJson,
                                                       AtomicReference<String> freshSecret) {
        var existing = webhooks.byIdempotency(userId, scope.sourceEnvironment(), scope.runId(), idempotencyKey);
        if (existing != null) return existingHash(existing, requestHash) ? ApiResult.ok(view(existing, null))
                : ApiResult.fail(409, "IDEMPOTENCY_PAYLOAD_CONFLICT");
        if (webhooks.countExisting(userId, scope.sourceEnvironment(), scope.runId()) >= accountWebhookLimit()) {
            return ApiResult.fail(409, "DEVELOPER_WEBHOOK_ACCOUNT_LIMIT_REACHED");
        }
        validateUrl(url);
        String secret = generateSecret();
        String ciphertext = WebhookSecretCrypto.encrypt(secret, environment);
        if (!StringUtils.hasText(ciphertext)) {
            return ApiResult.fail(503, "DEVELOPER_WEBHOOK_SECRET_KEY_UNAVAILABLE");
        }
        if (webhooks.insertWebhook(new AppDeveloperWebhookMapper.WebhookWrite(userId, idempotencyKey, requestHash, name, url,
                eventsJson, AppDeveloperApiService.hash(secret), ciphertext,
                scope.sourceEnvironment(), scope.runId())) != 1) {
            existing = webhooks.byIdempotency(userId, scope.sourceEnvironment(), scope.runId(), idempotencyKey);
            if (existing == null) return ApiResult.fail(409, "DEVELOPER_WEBHOOK_CONFLICT");
            return existingHash(existing, requestHash) ? ApiResult.ok(view(existing, null))
                    : ApiResult.fail(409, "IDEMPOTENCY_PAYLOAD_CONFLICT");
        }
        var inserted = webhooks.byIdempotency(userId, scope.sourceEnvironment(), scope.runId(), idempotencyKey);
        if (inserted == null) return ApiResult.fail(503, "DEVELOPER_WEBHOOK_RESULT_UNKNOWN");
        recordAudit("DEVELOPER_WEBHOOK_CREATED", inserted, userId, "CREATE");
        freshSecret.set(secret);
        return ApiResult.ok(view(inserted, null));
    }

    public ApiResult<List<Map<String, Object>>> list(Long userId) {
        try { var s = guard().requireApproved(userId, false); return ApiResult.ok(webhooks.listBounded(userId,
                s.sourceEnvironment(), s.runId(), accountWebhookLimit()).stream().map(row -> view(row, null)).toList()); }
        catch (BizException ex) { return ApiResult.fail(ex.getCode(), ex.getMessage()); }
    }

    private int accountWebhookLimit() {
        return Math.max(1, Math.min(100, environment.getProperty(
                "nexion.developer.webhooks.max-per-account", Integer.class, DEFAULT_ACCOUNT_WEBHOOK_LIMIT)));
    }

    @Transactional
    public ApiResult<Map<String, Object>> update(Long userId, Long id, String name, String url, String eventsJson, boolean rotateSecret) {
        return update(userId, id, name, url, eventsJson, rotateSecret, null);
    }

    @Transactional
    public ApiResult<Map<String, Object>> update(Long userId, Long id, String name, String url, String eventsJson,
                                                  boolean rotateSecret, String idempotencyKey) {
        try {
            var scope = guard().requireApproved(userId, true);
            String n = normalize(name), u = normalize(url), idem = normalize(idempotencyKey);
            List<String> events = events(eventsJson);
            if (id == null || id <= 0 || n == null || n.length() > 100 || idem == null || idem.length() > 128)
                return ApiResult.fail(422, "DEVELOPER_WEBHOOK_INVALID");
            String canonicalEvents = objectMapper.writeValueAsString(events);
            String requestHash = AppDeveloperApiService.hash("UPDATE\n" + id + "\n" + n + "\n" + u + "\n"
                    + canonicalEvents + "\n" + rotateSecret);
            AtomicReference<String> freshSecret = new AtomicReference<>();
            ApiResult<Map<String, Object>> result = once("APP_DEVELOPER_WEBHOOK_UPDATE:" + userId + ":" + id,
                    idem, requestHash, () -> updateOnce(userId, id, scope, n, u, canonicalEvents, rotateSecret, idem, freshSecret));
            if (result.getCode() == 0 && freshSecret.get() != null && result.getData() != null) {
                result.getData().put("secret", freshSecret.get());
            }
            return result;
        } catch (BizException ex) { return ApiResult.fail(ex.getCode(), ex.getMessage()); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { return ApiResult.fail(422, "DEVELOPER_WEBHOOK_INVALID"); }
    }

    private ApiResult<Map<String, Object>> updateOnce(Long userId, Long id, DeveloperAccountGuard.Scope scope,
                                                       String name, String url, String eventsJson, boolean rotateSecret,
                                                       String idempotencyKey, AtomicReference<String> freshSecret) {
        var row = webhooks.byId(id, userId, scope.sourceEnvironment(), scope.runId());
        if (row == null) return ApiResult.fail(404, "DEVELOPER_WEBHOOK_NOT_FOUND");
        validateUrl(url);
        if (rotateSecret && !WebhookSecretCrypto.configured(environment)) {
            return ApiResult.fail(503, "DEVELOPER_WEBHOOK_SECRET_KEY_UNAVAILABLE");
        }
        if (webhooks.update(new AppDeveloperWebhookMapper.WebhookUpdate(id, userId, name, url, eventsJson, row.version(),
                scope.sourceEnvironment(), scope.runId())) != 1) return ApiResult.fail(409, "DEVELOPER_WEBHOOK_CONCURRENT_UPDATE");
        var after = webhooks.byId(id, userId, scope.sourceEnvironment(), scope.runId());
        if (after == null) return ApiResult.fail(503, "DEVELOPER_WEBHOOK_RESULT_UNKNOWN");
        if (rotateSecret) {
            ApiResult<Map<String, Object>> rotated = rotateSecretOnce(userId, id, scope, after, idempotencyKey, freshSecret);
            if (rotated.getCode() != 0) return rotated;
            after = webhooks.byId(id, userId, scope.sourceEnvironment(), scope.runId());
            if (after == null) return ApiResult.fail(503, "DEVELOPER_WEBHOOK_RESULT_UNKNOWN");
        }
        recordAudit("DEVELOPER_WEBHOOK_UPDATED", after, userId, rotateSecret ? "UPDATE_AND_ROTATE" : "UPDATE");
        return ApiResult.ok(view(after, null));
    }

    @Transactional
    public ApiResult<Map<String, Object>> rotateSecret(Long userId, Long id) { return rotateSecret(userId, id, null); }

    @Transactional
    public ApiResult<Map<String, Object>> rotateSecret(Long userId, Long id, String idempotencyKey) {
        try {
            var scope = guard().requireApproved(userId, true);
            String idem = normalize(idempotencyKey);
            if (id == null || id <= 0 || idem == null || idem.length() > 128)
                return ApiResult.fail(422, "DEVELOPER_WEBHOOK_ROTATION_IDEMPOTENCY_REQUIRED");
            AtomicReference<String> freshSecret = new AtomicReference<>();
            ApiResult<Map<String, Object>> result = once("APP_DEVELOPER_WEBHOOK_ROTATE:" + userId + ":" + id, idem,
                    AppDeveloperApiService.hash("ROTATE\n" + id), () -> {
                        var row = webhooks.byId(id, userId, scope.sourceEnvironment(), scope.runId());
                        if (row == null) return ApiResult.fail(404, "DEVELOPER_WEBHOOK_NOT_FOUND");
                        // Legacy rows recorded the rotation key on the endpoint itself. Treat an exact replay as
                        // completed but never reveal the old credential or emit a second audit row.
                        if (idem.equals(row.rotationKey())) return ApiResult.ok(view(row, null));
                        ApiResult<Map<String, Object>> rotated = rotateSecretOnce(userId, id, scope, row, idem, freshSecret);
                        if (rotated.getCode() != 0) return rotated;
                        var after = webhooks.byId(id, userId, scope.sourceEnvironment(), scope.runId());
                        if (after == null) return ApiResult.fail(503, "DEVELOPER_WEBHOOK_RESULT_UNKNOWN");
                        recordAudit("DEVELOPER_WEBHOOK_SECRET_ROTATED", after, userId, "ROTATE_SECRET");
                        return ApiResult.ok(view(after, null));
                    });
            if (result.getCode() == 0 && freshSecret.get() != null && result.getData() != null) {
                result.getData().put("secret", freshSecret.get());
            }
            return result;
        } catch (BizException ex) { return ApiResult.fail(ex.getCode(), ex.getMessage()); }
    }

    private ApiResult<Map<String, Object>> rotateSecretOnce(Long userId, Long id, DeveloperAccountGuard.Scope scope,
                                                            AppDeveloperWebhookMapper.WebhookRow row, String idempotencyKey,
                                                            AtomicReference<String> freshSecret) {
        String secret = generateSecret();
        String secretHash = AppDeveloperApiService.hash(secret);
        String ciphertext = WebhookSecretCrypto.encrypt(secret, environment);
        if (!StringUtils.hasText(ciphertext)) {
            return ApiResult.fail(503, "DEVELOPER_WEBHOOK_SECRET_KEY_UNAVAILABLE");
        }
        int rotated = webhooks.rotateSecretWithCiphertext(id, userId, scope.sourceEnvironment(), scope.runId(), row.version(),
                secretHash, ciphertext, idempotencyKey, AppDeveloperApiService.hash(idempotencyKey + "\n" + secretHash));
        if (rotated != 1) return ApiResult.fail(409, "DEVELOPER_WEBHOOK_CONCURRENT_UPDATE");
        freshSecret.set(secret);
        return ApiResult.ok(Map.of());
    }

    @Transactional
    public ApiResult<Void> delete(Long userId, Long id, String idempotencyKey) {
        try {
            var scope = guard().requireApproved(userId, true);
            if (id == null || id <= 0) return ApiResult.fail(422, "DEVELOPER_WEBHOOK_INVALID");
            return onceVoid("APP_DEVELOPER_WEBHOOK_DELETE:" + userId + ":" + id, idempotencyKey,
                    AppDeveloperApiService.hash("DELETE\n" + id), () -> deleteOnce(userId, id, scope));
        }
        catch (BizException ex) { return ApiResult.fail(ex.getCode(), ex.getMessage()); }
    }

    private ApiResult<Void> deleteOnce(Long userId, Long id, DeveloperAccountGuard.Scope scope) {
        var row = webhooks.byId(id, userId, scope.sourceEnvironment(), scope.runId());
        if (row == null) return ApiResult.fail(404, "DEVELOPER_WEBHOOK_NOT_FOUND");
        if (webhooks.delete(id, userId, scope.sourceEnvironment(), scope.runId(), row.version()) != 1)
            return ApiResult.fail(409, "DEVELOPER_WEBHOOK_CONCURRENT_UPDATE");
        recordAudit("DEVELOPER_WEBHOOK_DELETED", row, userId, "DELETE");
        return ApiResult.ok();
    }

    @Transactional
    public ApiResult<Map<String, Object>> setEnabled(Long userId, Long id, boolean enabled, String idempotencyKey) {
        try {
            var scope = guard().requireApproved(userId, true);
            if (id == null || id <= 0) return ApiResult.fail(422, "DEVELOPER_WEBHOOK_INVALID");
            return once("APP_DEVELOPER_WEBHOOK_TOGGLE:" + userId + ":" + id, idempotencyKey,
                    AppDeveloperApiService.hash("TOGGLE\n" + id + "\n" + enabled),
                    () -> setEnabledOnce(userId, id, enabled, scope));
        } catch (BizException ex) { return ApiResult.fail(ex.getCode(), ex.getMessage()); }
    }

    private ApiResult<Map<String, Object>> setEnabledOnce(Long userId, Long id, boolean enabled,
                                                           DeveloperAccountGuard.Scope scope) {
        var row = webhooks.byId(id, userId, scope.sourceEnvironment(), scope.runId());
        if (row == null) return ApiResult.fail(404, "DEVELOPER_WEBHOOK_NOT_FOUND");
        if (enabled && !StringUtils.hasText(row.secretCiphertext())) {
            return ApiResult.fail(409, "DEVELOPER_WEBHOOK_SECRET_UNAVAILABLE");
        }
        if (webhooks.setStatus(id, userId, scope.sourceEnvironment(), scope.runId(), row.version(),
                enabled ? "ACTIVE" : "DISABLED") != 1) return ApiResult.fail(409, "DEVELOPER_WEBHOOK_CONCURRENT_UPDATE");
        var after = webhooks.byId(id, userId, scope.sourceEnvironment(), scope.runId());
        var result = after == null ? row : after;
        recordAudit(enabled ? "DEVELOPER_WEBHOOK_ENABLED" : "DEVELOPER_WEBHOOK_DISABLED", result, userId,
                enabled ? "ENABLE" : "DISABLE");
        return ApiResult.ok(view(result, null));
    }

    public ApiResult<Map<String, Object>> deliveryLog(Long userId, Long id, Long beforeId, int limit) {
        try {
            if (deliveries == null) return ApiResult.fail(503, "DEVELOPER_WEBHOOK_DELIVERY_UNAVAILABLE");
            if (beforeId != null && beforeId <= 0) return ApiResult.fail(422, "DEVELOPER_WEBHOOK_DELIVERY_CURSOR_INVALID");
            var s = guard().requireApproved(userId, false);
            if (webhooks.byId(id, userId, s.sourceEnvironment(), s.runId()) == null) return ApiResult.fail(404, "DEVELOPER_WEBHOOK_NOT_FOUND");
            int pageSize = Math.max(1, Math.min(limit, 100));
            var rows = deliveries.listForWebhookBefore(id, userId, s.sourceEnvironment(), s.runId(), beforeId,
                    pageSize + 1);
            boolean hasMore = rows.size() > pageSize;
            var pageRows = hasMore ? rows.subList(0, pageSize) : rows;
            String nextCursor = hasMore && !pageRows.isEmpty()
                    ? String.valueOf(pageRows.get(pageRows.size() - 1).id()) : null;
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("items", pageRows.stream().map(this::deliveryView).toList());
            response.put("hasMore", hasMore);
            response.put("nextCursor", nextCursor);
            return ApiResult.ok(response);
        } catch (BizException ex) { return ApiResult.fail(ex.getCode(), ex.getMessage()); }
    }

    public static Set<String> eventAllowlist() { return EVENT_ALLOWLIST; }

    private Map<String, Object> deliveryView(ffdd.opsconsole.developer.mapper.AppDeveloperWebhookDeliveryMapper.DeliveryRow row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.id()); out.put("eventId", row.eventId()); out.put("eventType", row.eventType());
        out.put("status", row.status()); out.put("attemptCount", row.attemptCount()); out.put("maxAttempts", row.maxAttempts());
        out.put("lastStatusCode", row.lastStatusCode()); out.put("lastError", row.lastError());
        out.put("nextRetryAt", row.nextRetryAt()); out.put("createdAt", row.createdAt()); out.put("updatedAt", row.updatedAt());
        return out;
    }

    private boolean existingHash(AppDeveloperWebhookMapper.WebhookRow row, String hash) {
        return hash.equals(row.requestHash());
    }
    private DeveloperAccountGuard guard() { return new DeveloperAccountGuard(access, environment); }
    private List<String> events(String raw) {
        if (!StringUtils.hasText(raw)) throw new BizException(422, "DEVELOPER_WEBHOOK_EVENTS_REQUIRED");
        try {
            List<String> parsed = objectMapper.readValue(raw, new TypeReference<>() {});
            if (parsed == null || parsed.isEmpty() || parsed.size() > EVENT_ALLOWLIST.size()
                    || parsed.stream().anyMatch(e -> e == null || !EVENT_ALLOWLIST.contains(e)))
                throw new BizException(422, "DEVELOPER_WEBHOOK_EVENT_NOT_ALLOWED");
            return parsed.stream().distinct().toList();
        } catch (BizException ex) { throw ex;
        } catch (Exception ex) { throw new BizException(422, "DEVELOPER_WEBHOOK_EVENT_NOT_ALLOWED"); }
    }
    private void validateUrl(String raw) {
        if (!StringUtils.hasText(raw)) throw new BizException(422, "DEVELOPER_WEBHOOK_URL_REQUIRED");
        java.net.URI uri = DeveloperWebhookUrlValidator.validate(raw, environment);
        // Create/update must prove the same public DNS resolution as the delivery transport. The transport
        // resolves again immediately before its fixed-address socket connect, so DNS rebinding cannot swap a
        // UI-accepted host to a private address between configuration and delivery.
        DeveloperWebhookUrlValidator.rejectResolvedPrivateAddresses(uri, environment);
    }
    private String normalize(String value) { return value == null || value.trim().isEmpty() ? null : value.trim(); }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private ApiResult<Map<String, Object>> once(String scope, String idempotencyKey, String requestHash,
                                                 Supplier<ApiResult<Map<String, Object>>> action) {
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotency.execute(scope, idempotencyKey, requestHash,
                ApiResult.class, (Supplier) action);
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private ApiResult<Void> onceVoid(String scope, String idempotencyKey, String requestHash,
                                     Supplier<ApiResult<Void>> action) {
        return (ApiResult<Void>) (ApiResult) idempotency.execute(scope, idempotencyKey, requestHash,
                ApiResult.class, (Supplier) action);
    }
    private void recordAudit(String action, AppDeveloperWebhookMapper.WebhookRow row, Long userId, String operation) {
        audit.recordRequired(AuditLogWriteRequest.builder()
                .action(action).resourceType("DEVELOPER_WEBHOOK").resourceId(String.valueOf(row.id()))
                .userId(userId).actorType("USER").result("SUCCESS").riskLevel("HIGH")
                // Do not include endpoint URL, idempotency key, secret/ciphertext, or request payload in audits.
                .detail(Map.of("operation", operation, "status", row.status(), "eventCount", safeEventCount(row.eventsJson()),
                        "version", row.version(), "sourceEnvironment", row.sourceEnvironment(), "runId", row.runId()))
                .build());
    }
    private int safeEventCount(String eventsJson) {
        try { return objectMapper.readValue(eventsJson, new TypeReference<List<String>>() {}).size(); }
        catch (Exception ignored) { return 0; }
    }
    private String generateSecret() { return "whsec_" + UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""); }
    private Map<String, Object> view(AppDeveloperWebhookMapper.WebhookRow row, String secret) {
        Map<String, Object> out = new LinkedHashMap<>(); out.put("id", row.id()); out.put("name", row.name()); out.put("url", row.url()); try { out.put("events", objectMapper.readValue(row.eventsJson(), new TypeReference<List<String>>() {})); } catch (Exception ex) { out.put("events", List.of()); } out.put("status", row.status()); out.put("deliveryStatus", row.deliveryStatus() == null ? DELIVERY_STATUS : row.deliveryStatus()); out.put("deliveryEnabled", "ACTIVE".equals(row.status()) && StringUtils.hasText(row.secretCiphertext())); out.put("source", "server"); out.put("sourceEnvironment", row.sourceEnvironment()); out.put("runId", row.runId()); out.put("createdAt", row.createdAt().atOffset(ZoneOffset.UTC).toInstant().toString()); if (secret != null) out.put("secret", secret); return out;
    }
}
