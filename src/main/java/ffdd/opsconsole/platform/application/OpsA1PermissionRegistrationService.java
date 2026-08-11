package ffdd.opsconsole.platform.application;

import ffdd.opsconsole.platform.dto.A1PermissionRegistrationRequest;
import ffdd.opsconsole.platform.dto.PermissionDictionaryView;
import ffdd.opsconsole.platform.mapper.AdminPermissionMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class OpsA1PermissionRegistrationService {
    private static final Set<String> TYPES = Set.of("READ", "WRITE", "HIGH");
    private final AdminPermissionMapper permissionMapper;
    private final AdminIdempotencyService idempotency;
    private final AuditLogService audit;

    @SuppressWarnings({"rawtypes", "unchecked"})
    public ApiResult<PermissionDictionaryView> register(
            String idempotencyKey, A1PermissionRegistrationRequest request) {
        if (!StringUtils.hasText(idempotencyKey)) return ApiResult.fail(400, "IDEMPOTENCY_KEY_REQUIRED");
        Normalized value;
        try {
            value = normalize(request);
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(422, ex.getMessage());
        }
        String actor = AdminActorResolver.resolve(request.operator());
        String hash = sha256(value + "|" + actor);
        return (ApiResult<PermissionDictionaryView>) (ApiResult) idempotency.execute(
                "A1_PERMISSION_REGISTER", idempotencyKey.trim(), hash, ApiResult.class,
                () -> registerOnce(idempotencyKey.trim(), value, actor));
    }

    @Transactional
    ApiResult<PermissionDictionaryView> registerOnce(String idempotencyKey, Normalized value, String actor) {
        if (permissionMapper.selectPermissionDetail(value.permissionCode()) != null) {
            return ApiResult.fail(409, "A1_PERMISSION_ALREADY_EXISTS");
        }
        if (permissionMapper.insertUnassignedPermission(
                value.permissionCode(), value.permissionName(), value.permType(), value.amplifies() ? 1 : 0,
                value.resourcePath(), "A1 registered; reason=" + value.reason()) != 1) {
            return ApiResult.fail(409, "A1_PERMISSION_ALREADY_EXISTS");
        }
        PermissionDictionaryView created = permissionMapper.selectPermissionDetail(value.permissionCode());
        if (created == null || created.boundRoleCount() == null || created.boundRoleCount() != 0) {
            throw new IllegalStateException("A1_PERMISSION_MINIMUM_GRANT_CHECK_FAILED");
        }
        audit.recordRequired(AuditLogWriteRequest.builder()
                .action("A1_PERMISSION_REGISTERED").resourceType("A1_PERMISSION")
                .resourceId(value.permissionCode()).actorType("ADMIN").actorUsername(actor)
                .result("SUCCESS").riskLevel("HIGH")
                .detail(Map.of("permissionCode", value.permissionCode(), "resourcePath", value.resourcePath(),
                        "permType", value.permType(), "amplifies", value.amplifies(),
                        "boundRoleCount", created.boundRoleCount(), "reason", value.reason(),
                        "idempotencyKey", idempotencyKey)).build());
        return ApiResult.ok(created);
    }

    private Normalized normalize(A1PermissionRegistrationRequest request) {
        if (request == null || !Boolean.TRUE.equals(request.expectedAbsent())) throw invalid("A1_PERMISSION_EXPECTED_ABSENT_REQUIRED");
        String code = text(request.permissionCode(), "A1_PERMISSION_CODE_REQUIRED").toLowerCase(Locale.ROOT);
        if (!code.matches("[a-z][a-z0-9_]{5,95}")) throw invalid("A1_PERMISSION_CODE_INVALID");
        String name = text(request.permissionName(), "A1_PERMISSION_NAME_REQUIRED");
        if (name.length() > 96) throw invalid("A1_PERMISSION_NAME_INVALID");
        String path = text(request.resourcePath(), "A1_PERMISSION_PATH_REQUIRED");
        if (!path.startsWith("/api/admin/") || path.contains("..") || path.length() > 255) {
            throw invalid("A1_PERMISSION_PATH_INVALID");
        }
        String type = text(request.permType(), "A1_PERMISSION_TYPE_REQUIRED").toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) throw invalid("A1_PERMISSION_TYPE_INVALID");
        String reason = text(request.reason(), "REASON_REQUIRED");
        if (reason.length() < 8 || reason.length() > 200) throw invalid("A1_PERMISSION_REASON_INVALID");
        boolean amplifies = Boolean.TRUE.equals(request.amplifies());
        if (amplifies && !"HIGH".equals(type)) throw invalid("A1_PERMISSION_AMPLIFY_REQUIRES_HIGH");
        return new Normalized(code, name, path, type, amplifies, reason);
    }

    private String text(String value, String error) {
        if (!StringUtils.hasText(value)) throw invalid(error);
        return value.trim();
    }

    private IllegalArgumentException invalid(String value) { return new IllegalArgumentException(value); }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    record Normalized(String permissionCode, String permissionName, String resourcePath,
                      String permType, boolean amplifies, String reason) {
    }
}
