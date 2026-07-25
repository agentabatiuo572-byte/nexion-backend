package ffdd.opsconsole.janus.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.janus.domain.JanusRemoteTargetCreateCommand;
import ffdd.opsconsole.janus.domain.JanusRemoteTargetRepository;
import ffdd.opsconsole.janus.domain.JanusRemoteTargetView;
import ffdd.opsconsole.janus.dto.JanusRemoteTargetCreateRequest;
import ffdd.opsconsole.janus.dto.JanusRemoteTargetDisableRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class OpsJanusRemoteTargetService {
    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9-]{1,63}$");
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(?:\\.\\d{1,3}){3}$");

    private final JanusRemoteTargetRepository repository;
    private final AdminIdempotencyService idempotency;
    private final AuditLogService audit;
    private final ObjectMapper objectMapper;
    private final JanusRemoteTargetProperties properties;
    private final JanusRemoteTargetNetworkGuard networkGuard;

    public ApiResult<List<JanusRemoteTargetView>> list() {
        return ApiResult.ok(repository.list());
    }

    public ApiResult<List<String>> allowedOrigins() {
        return ApiResult.ok(properties.normalizedAllowedOrigins());
    }

    public ApiResult<JanusRemoteTargetView> create(String idempotencyKey,
                                                    JanusRemoteTargetCreateRequest request) {
        NormalizedTarget normalized = normalizeCreate(request);
        if (normalized.error() != null) return ApiResult.fail(422, normalized.error());
        String actor = currentActor();
        JanusRemoteTargetCreateCommand command = new JanusRemoteTargetCreateCommand(
                normalized.key(), normalized.label(), normalized.url(), normalized.origin(),
                normalized.owner(), request.expectedLatestVersion(), normalized.reason(), normalized.impact(), actor);
        return execute("K6_REMOTE_TARGET_CREATE", idempotencyKey, command, () -> {
            JanusRemoteTargetView created = repository.createVersion(command);
            if (created == null) return ApiResult.fail(409, "JANUS_REMOTE_TARGET_VERSION_CONFLICT");
            audit.recordRequired(AuditLogWriteRequest.builder()
                    .action("K6_REMOTE_TARGET_VERSION_CREATED")
                    .resourceType("JANUS_REMOTE_TARGET")
                    .resourceId(created.remoteTargetKey() + ":" + created.remoteTargetVersion())
                    .actorUsername(actor)
                    .riskLevel("CRITICAL")
                    .detail(Map.of(
                            "remoteTargetKey", created.remoteTargetKey(),
                            "remoteTargetVersion", created.remoteTargetVersion(),
                            "origin", created.origin(),
                            "ownerId", created.ownerId(),
                            "reason", normalized.reason(),
                            "impact", normalized.impact(),
                            "idempotencyKey", idempotencyKey.trim()))
                    .build());
            return ApiResult.ok(created);
        });
    }

    public ApiResult<JanusRemoteTargetView> disable(String key, int version, String idempotencyKey,
                                                     JanusRemoteTargetDisableRequest request) {
        String normalizedKey = normalizeKey(key);
        String error = validateDisable(normalizedKey, version, request);
        if (error != null) return ApiResult.fail(422, error);
        String reason = request.reason().trim();
        String impact = request.impact().trim();
        String actor = currentActor();
        Map<String, Object> hashInput = Map.of(
                "key", normalizedKey, "version", version,
                "catalogVersion", request.expectedCatalogVersion(),
                "expectedVersion", request.expectedVersion(),
                "reason", reason, "impact", impact);
        return execute("K6_REMOTE_TARGET_DISABLE", idempotencyKey, hashInput, () -> {
            JanusRemoteTargetView before = repository.find(normalizedKey, version).orElse(null);
            if (before == null) return ApiResult.fail(404, "JANUS_REMOTE_TARGET_NOT_FOUND");
            if (!"ACTIVE".equals(before.status())) {
                return ApiResult.fail(409, "JANUS_REMOTE_TARGET_ALREADY_DISABLED");
            }
            if (before.catalogVersion() != request.expectedCatalogVersion()) {
                return ApiResult.fail(409, "JANUS_REMOTE_TARGET_CATALOG_VERSION_CONFLICT");
            }
            JanusRemoteTargetView disabled = repository.disableVersion(
                    normalizedKey, version, request.expectedCatalogVersion(), request.expectedVersion(), actor);
            if (disabled == null) return ApiResult.fail(409, "JANUS_REMOTE_TARGET_VERSION_CONFLICT");
            int cancelled = repository.cancelUnclaimedCommands(
                    normalizedKey, version, request.expectedCatalogVersion());
            JanusRemoteTargetView result = withCancelledCount(disabled, cancelled);
            audit.recordRequired(AuditLogWriteRequest.builder()
                    .action("K6_REMOTE_TARGET_DISABLED")
                    .resourceType("JANUS_REMOTE_TARGET")
                    .resourceId(normalizedKey + ":" + version)
                    .actorUsername(actor)
                    .riskLevel("CRITICAL")
                    .detail(Map.of(
                            "beforeStatus", before.status(),
                            "afterStatus", result.status(),
                            "catalogVersion", result.catalogVersion(),
                            "cancelledUnclaimedCommands", cancelled,
                            "reason", reason,
                            "impact", impact,
                            "idempotencyKey", idempotencyKey.trim()))
                    .build());
            return ApiResult.ok(result);
        });
    }

    private String validateDisable(String key, int version, JanusRemoteTargetDisableRequest request) {
        if (key == null) return "JANUS_REMOTE_TARGET_KEY_INVALID";
        if (version < 1) return "JANUS_REMOTE_TARGET_VERSION_INVALID";
        if (request == null || request.expectedVersion() == null || request.expectedVersion() < 0) {
            return "EXPECTED_VERSION_REQUIRED";
        }
        if (request.expectedCatalogVersion() == null || request.expectedCatalogVersion() < 1) {
            return "EXPECTED_CATALOG_VERSION_REQUIRED";
        }
        if (!validReason(request.reason())) return "REASON_REQUIRED";
        if (!validReason(request.impact())) return "IMPACT_REQUIRED";
        return null;
    }

    private NormalizedTarget normalizeCreate(JanusRemoteTargetCreateRequest request) {
        if (request == null) return NormalizedTarget.error("JANUS_REMOTE_TARGET_REQUIRED");
        String key = normalizeKey(request.remoteTargetKey());
        if (key == null) return NormalizedTarget.error("JANUS_REMOTE_TARGET_KEY_INVALID");
        String label = trim(request.label());
        if (label == null || label.length() < 2 || label.length() > 96) {
            return NormalizedTarget.error("JANUS_REMOTE_TARGET_LABEL_INVALID");
        }
        String owner = trim(request.ownerId());
        if (owner == null || owner.length() < 2 || owner.length() > 96) {
            return NormalizedTarget.error("JANUS_REMOTE_TARGET_OWNER_INVALID");
        }
        if (request.expectedLatestVersion() == null || request.expectedLatestVersion() < 0) {
            return NormalizedTarget.error("EXPECTED_LATEST_VERSION_REQUIRED");
        }
        String reason = trim(request.reason());
        if (!validReason(reason)) return NormalizedTarget.error("REASON_REQUIRED");
        String impact = trim(request.impact());
        if (!validReason(impact)) return NormalizedTarget.error("IMPACT_REQUIRED");
        UrlParts url = normalizeUrl(request.url());
        if (url == null) return NormalizedTarget.error("JANUS_REMOTE_TARGET_HTTPS_INVALID");
        return new NormalizedTarget(key, label, url.url(), url.origin(), owner, reason, impact, null);
    }

    private UrlParts normalizeUrl(String value) {
        String raw = trim(value);
        if (raw == null || raw.length() > 1024) return null;
        try {
            URI uri = new URI(raw).normalize();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !uri.isAbsolute()
                    || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getFragment() != null || uri.getQuery() != null) {
                return null;
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (unsafeHost(host)) return null;
            int port = uri.getPort();
            if (port == 0 || port > 65535) return null;
            String origin = "https://" + (host.contains(":") ? "[" + host + "]" : host)
                    + (port > 0 && port != 443 ? ":" + port : "");
            if (!properties.allows(origin)) return null;
            if (!networkGuard.allows(uri)) return null;
            return new UrlParts(uri.toASCIIString(), origin);
        } catch (URISyntaxException ex) {
            return null;
        }
    }

    private boolean unsafeHost(String host) {
        if (host.equals("localhost") || host.endsWith(".localhost")
                || host.endsWith(".local") || host.endsWith(".internal") || host.contains(":")) {
            return true;
        }
        if (!IPV4.matcher(host).matches()) return false;
        String[] parts = host.split("\\.");
        int[] octets = new int[4];
        for (int i = 0; i < parts.length; i++) {
            try {
                octets[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException ex) {
                return true;
            }
            if (octets[i] < 0 || octets[i] > 255) return true;
        }
        return octets[0] == 0 || octets[0] == 10 || octets[0] == 127 || octets[0] >= 224
                || octets[0] == 169 && octets[1] == 254
                || octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31
                || octets[0] == 192 && octets[1] == 168;
    }

    private String normalizeKey(String value) {
        String key = trim(value);
        return key != null && KEY.matcher(key).matches() ? key : null;
    }

    private boolean validReason(String value) {
        String normalized = trim(value);
        return normalized != null && normalized.length() >= 8 && normalized.length() <= 500;
    }

    private String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private <T> ApiResult<T> execute(String scope, String idempotencyKey, Object request,
                                     java.util.function.Supplier<ApiResult<T>> action) {
        return (ApiResult<T>) idempotency.execute(
                scope, idempotencyKey, hash(request), ApiResult.class, (java.util.function.Supplier) action);
    }

    private String hash(Object value) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(value);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JANUS_REMOTE_TARGET_HASH_FAILED", ex);
        }
    }

    private String currentActor() {
        return AdminActorResolver.resolve("system");
    }

    private JanusRemoteTargetView withCancelledCount(JanusRemoteTargetView value, int cancelled) {
        return new JanusRemoteTargetView(
                value.catalogVersion(), value.remoteTargetKey(), value.remoteTargetVersion(), value.status(),
                value.label(), value.url(), value.origin(), value.source(), value.ownerId(), value.createdAt(),
                value.updatedAt(), value.updatedBy(), value.changeReason(), value.impact(), value.lockVersion(),
                value.strategyCount(), 0, cancelled);
    }

    private record UrlParts(String url, String origin) {
    }

    private record NormalizedTarget(String key, String label, String url, String origin, String owner,
                                    String reason, String impact, String error) {
        static NormalizedTarget error(String error) {
            return new NormalizedTarget(null, null, null, null, null, null, null, error);
        }
    }
}
