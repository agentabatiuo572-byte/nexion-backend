package ffdd.opsconsole.developer.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.developer.mapper.AppDeveloperAccessMapper;
import ffdd.opsconsole.developer.mapper.AppDeveloperWebhookMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.net.URI;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AppDeveloperWebhookService {
    public static final String DELIVERY_STATUS = "NOT_DELIVERED";
    private static final Set<String> EVENT_ALLOWLIST = Set.of("order.updated", "order.completed", "compute.job.completed",
            "compute.job.failed", "earnings.updated", "billing.invoice.created", "market.updated", "account.updated");
    private final AppDeveloperWebhookMapper webhooks;
    private final AppDeveloperAccessMapper access;
    private final Environment environment;
    private final ffdd.opsconsole.developer.mapper.AppDeveloperWebhookDeliveryMapper deliveries;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public ApiResult<Map<String, Object>> create(Long userId, String name, String url, String eventsJson, String idempotencyKey) {
        try {
            var scope = guard().requireApproved(userId, true);
            String n = normalize(name), u = normalize(url), idem = normalize(idempotencyKey);
            List<String> events = events(eventsJson);
            if (n == null || n.length() > 100 || idem == null || idem.length() > 128) return ApiResult.fail(422, "DEVELOPER_WEBHOOK_INVALID");
            validateUrl(u);
            String canonicalEvents = objectMapper.writeValueAsString(events);
            String requestHash = AppDeveloperApiService.hash(n + "\n" + u + "\n" + canonicalEvents);
            var existing = webhooks.byIdempotency(userId, scope.sourceEnvironment(), scope.runId(), idem);
            if (existing != null) return existingHash(existing, requestHash) ? ApiResult.ok(view(existing, null)) : ApiResult.fail(409, "IDEMPOTENCY_PAYLOAD_CONFLICT");
            String secret = generateSecret();
            if (webhooks.insertWebhook(new AppDeveloperWebhookMapper.WebhookWrite(userId, idem, requestHash, n, u, canonicalEvents,
                    AppDeveloperApiService.hash(secret), WebhookSecretCrypto.encrypt(secret, environment), scope.sourceEnvironment(), scope.runId())) != 1) {
                existing = webhooks.byIdempotency(userId, scope.sourceEnvironment(), scope.runId(), idem);
                if (existing == null) return ApiResult.fail(409, "DEVELOPER_WEBHOOK_CONFLICT");
                return existingHash(existing, requestHash)
                        ? ApiResult.ok(view(existing, null))
                        : ApiResult.fail(409, "IDEMPOTENCY_PAYLOAD_CONFLICT");
            }
            var inserted = webhooks.byIdempotency(userId, scope.sourceEnvironment(), scope.runId(), idem);
            return inserted == null ? ApiResult.fail(503, "DEVELOPER_WEBHOOK_RESULT_UNKNOWN") : ApiResult.ok(view(inserted, secret));
        } catch (BizException ex) { return ApiResult.fail(ex.getCode(), ex.getMessage()); }
        catch (Exception ex) { return ApiResult.fail(422, "DEVELOPER_WEBHOOK_INVALID"); }
    }

    public ApiResult<List<Map<String, Object>>> list(Long userId) {
        try { var s = guard().requireApproved(userId, false); return ApiResult.ok(webhooks.list(userId, s.sourceEnvironment(), s.runId()).stream().map(row -> view(row, null)).toList()); }
        catch (BizException ex) { return ApiResult.fail(ex.getCode(), ex.getMessage()); }
    }

    @Transactional
    public ApiResult<Map<String, Object>> update(Long userId, Long id, String name, String url, String eventsJson, boolean rotateSecret) {
        return update(userId, id, name, url, eventsJson, rotateSecret, null);
    }

    @Transactional
    public ApiResult<Map<String, Object>> update(Long userId, Long id, String name, String url, String eventsJson, boolean rotateSecret, String rotationKey) {
        try {
            var s = guard().requireApproved(userId, true); var row = webhooks.byId(id, userId, s.sourceEnvironment(), s.runId());
            if (row == null) return ApiResult.fail(404, "DEVELOPER_WEBHOOK_NOT_FOUND");
            String n = normalize(name), u = normalize(url); List<String> events = events(eventsJson); validateUrl(u);
            if (webhooks.update(new AppDeveloperWebhookMapper.WebhookUpdate(id, userId, n, u, objectMapper.writeValueAsString(events), row.version(), s.sourceEnvironment(), s.runId())) != 1) return ApiResult.fail(409, "DEVELOPER_WEBHOOK_CONCURRENT_UPDATE");
            String secret = null;
            if (rotateSecret) return rotateSecret(userId, id, rotationKey);
            var after = webhooks.byId(id, userId, s.sourceEnvironment(), s.runId());
            return ApiResult.ok(view(after == null ? row : after, secret));
        } catch (BizException ex) { return ApiResult.fail(ex.getCode(), ex.getMessage()); }
        catch (Exception ex) { return ApiResult.fail(422, "DEVELOPER_WEBHOOK_INVALID"); }
    }

    @Transactional
    public ApiResult<Map<String, Object>> rotateSecret(Long userId, Long id) { return rotateSecret(userId, id, null); }

    @Transactional
    public ApiResult<Map<String, Object>> rotateSecret(Long userId, Long id, String rotationKey) {
        try {
            var s = guard().requireApproved(userId, true);
            var row = webhooks.byId(id, userId, s.sourceEnvironment(), s.runId());
            if (row == null) return ApiResult.fail(404, "DEVELOPER_WEBHOOK_NOT_FOUND");
            String idem = normalize(rotationKey);
            if (idem == null || idem.length() > 128) return ApiResult.fail(422, "DEVELOPER_WEBHOOK_ROTATION_IDEMPOTENCY_REQUIRED");
            if (idem.equals(row.rotationKey())) return ApiResult.fail(409, "DEVELOPER_WEBHOOK_SECRET_ROTATION_REPLAY");
            String secret = generateSecret();
            String secretHash = AppDeveloperApiService.hash(secret);
            String ciphertext = WebhookSecretCrypto.encrypt(secret, environment);
            int rotated = ciphertext == null
                    ? webhooks.rotateSecret(id, userId, s.sourceEnvironment(), s.runId(), row.version(), secretHash, idem, AppDeveloperApiService.hash(idem + "\n" + secretHash))
                    : webhooks.rotateSecretWithCiphertext(id, userId, s.sourceEnvironment(), s.runId(), row.version(), secretHash, ciphertext, idem, AppDeveloperApiService.hash(idem + "\n" + secretHash));
            if (rotated != 1) return ApiResult.fail(409, "DEVELOPER_WEBHOOK_CONCURRENT_UPDATE");
            var after = webhooks.byId(id, userId, s.sourceEnvironment(), s.runId());
            return ApiResult.ok(view(after == null ? row : after, secret));
        } catch (BizException ex) { return ApiResult.fail(ex.getCode(), ex.getMessage()); }
    }

    @Transactional
    public ApiResult<Void> delete(Long userId, Long id) {
        try { var s = guard().requireApproved(userId, true); var row = webhooks.byId(id, userId, s.sourceEnvironment(), s.runId()); if (row == null) return ApiResult.fail(404, "DEVELOPER_WEBHOOK_NOT_FOUND"); if (webhooks.delete(id, userId, s.sourceEnvironment(), s.runId(), row.version()) != 1) return ApiResult.fail(409, "DEVELOPER_WEBHOOK_CONCURRENT_UPDATE"); return ApiResult.ok(); }
        catch (BizException ex) { return ApiResult.fail(ex.getCode(), ex.getMessage()); }
    }

    @Transactional
    public ApiResult<Map<String, Object>> setEnabled(Long userId, Long id, boolean enabled) {
        try {
            var s = guard().requireApproved(userId, true);
            var row = webhooks.byId(id, userId, s.sourceEnvironment(), s.runId());
            if (row == null) return ApiResult.fail(404, "DEVELOPER_WEBHOOK_NOT_FOUND");
            if (webhooks.setStatus(id, userId, s.sourceEnvironment(), s.runId(), row.version(), enabled ? "ACTIVE" : "DISABLED") != 1)
                return ApiResult.fail(409, "DEVELOPER_WEBHOOK_CONCURRENT_UPDATE");
            var after = webhooks.byId(id, userId, s.sourceEnvironment(), s.runId());
            return ApiResult.ok(view(after == null ? row : after, null));
        } catch (BizException ex) { return ApiResult.fail(ex.getCode(), ex.getMessage()); }
    }

    public ApiResult<List<Map<String, Object>>> deliveryLog(Long userId, Long id, int limit) {
        try {
            if (deliveries == null) return ApiResult.fail(503, "DEVELOPER_WEBHOOK_DELIVERY_UNAVAILABLE");
            var s = guard().requireApproved(userId, false);
            if (webhooks.byId(id, userId, s.sourceEnvironment(), s.runId()) == null) return ApiResult.fail(404, "DEVELOPER_WEBHOOK_NOT_FOUND");
            return ApiResult.ok(deliveries.listForWebhook(id, userId, s.sourceEnvironment(), s.runId(), Math.max(1, Math.min(limit, 100)))
                    .stream().map(this::deliveryView).toList());
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
    private List<String> events(String raw) throws Exception {
        if (!StringUtils.hasText(raw)) throw new BizException(422, "DEVELOPER_WEBHOOK_EVENTS_REQUIRED");
        List<String> parsed = objectMapper.readValue(raw, new TypeReference<>() {});
        if (parsed == null || parsed.isEmpty() || parsed.size() > EVENT_ALLOWLIST.size() || parsed.stream().anyMatch(e -> e == null || !EVENT_ALLOWLIST.contains(e))) throw new BizException(422, "DEVELOPER_WEBHOOK_EVENT_NOT_ALLOWED");
        return parsed.stream().distinct().toList();
    }
    private void validateUrl(String raw) {
        if (!StringUtils.hasText(raw)) throw new BizException(422, "DEVELOPER_WEBHOOK_URL_REQUIRED");
        try {
            URI uri = URI.create(raw); String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT); String host = uri.getHost();
            if (host == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) throw new BizException(422, "DEVELOPER_WEBHOOK_URL_INVALID");
            if (isLocalProfile() && isLoopback(host) && "http".equals(scheme) && Boolean.parseBoolean(environment.getProperty("nexion.developer.webhooks.allow-loopback", "false"))) return;
            if (!"https".equals(scheme)) throw new BizException(422, "DEVELOPER_WEBHOOK_HTTPS_REQUIRED");
            if (isPrivateLiteral(host)) throw new BizException(422, "DEVELOPER_WEBHOOK_HOST_NOT_ALLOWED");
            String allowed = environment.getProperty("nexion.developer.webhooks.allowed-hosts", "");
            boolean matches = java.util.Arrays.stream(allowed.split(",")).map(String::trim).filter(v -> !v.isBlank()).anyMatch(v -> host.equalsIgnoreCase(v) || host.toLowerCase(Locale.ROOT).endsWith("." + v.toLowerCase(Locale.ROOT)));
            if (!matches) throw new BizException(422, "DEVELOPER_WEBHOOK_HOST_NOT_ALLOWED");
        } catch (BizException ex) {
            throw ex;
        } catch (IllegalArgumentException ex) {
            throw new BizException(422, "DEVELOPER_WEBHOOK_URL_INVALID");
        }
    }
    private boolean isLocalProfile() {
        return java.util.Arrays.stream(environment.getActiveProfiles())
                .map(profile -> profile.toLowerCase(Locale.ROOT))
                .anyMatch(Set.of("dev", "test")::contains);
    }
    private boolean isLoopback(String host) { return Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(host.toLowerCase(Locale.ROOT)); }
    private boolean isPrivateLiteral(String host) { String h = host.toLowerCase(Locale.ROOT); return isLoopback(h) || Set.of("metadata", "metadata.google.internal", "instance-data").contains(h) || h.equals("0.0.0.0") || h.startsWith("10.") || h.startsWith("192.168.") || h.startsWith("169.254.") || h.matches("172\\.(1[6-9]|2\\d|3[0-1])\\..*"); }
    private String normalize(String value) { return value == null || value.trim().isEmpty() ? null : value.trim(); }
    private String generateSecret() { return "whsec_" + UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""); }
    private Map<String, Object> view(AppDeveloperWebhookMapper.WebhookRow row, String secret) {
        Map<String, Object> out = new LinkedHashMap<>(); out.put("id", row.id()); out.put("name", row.name()); out.put("url", row.url()); try { out.put("events", objectMapper.readValue(row.eventsJson(), new TypeReference<List<String>>() {})); } catch (Exception ex) { out.put("events", List.of()); } out.put("status", row.status()); out.put("deliveryStatus", row.deliveryStatus() == null ? DELIVERY_STATUS : row.deliveryStatus()); out.put("deliveryEnabled", "ACTIVE".equals(row.status())); out.put("source", "server"); out.put("sourceEnvironment", row.sourceEnvironment()); out.put("runId", row.runId()); out.put("createdAt", row.createdAt().atOffset(ZoneOffset.UTC).toInstant().toString()); if (secret != null) out.put("secret", secret); return out;
    }
}
