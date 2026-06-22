package ffdd.opsconsole.platform.application;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.platform.dto.AdminCommandRequest;
import ffdd.opsconsole.platform.dto.AdminCommandResponse;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsAdminCommandService {
    private static final Pattern PARAM_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
    private static final String IDEMPOTENCY_SCOPE = "ADMIN_COMMAND";

    private final PlatformConfigFacade configFacade;
    private final AuditLogService auditLogService;
    private final AdminIdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public ApiResult<AdminCommandResponse> accept(String idempotencyKey, AdminCommandRequest request) {
        ApiResult<AdminCommandResponse> guard = requireCommand(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        if (containsSunsetTerm(request.action()) || containsSunsetTerm(request.resourceType())
                || containsSunsetTerm(request.resourceId()) || containsSunsetTerm(request.paramKey())) {
            return ApiResult.fail(OpsErrorCode.PHASE_PARAM_READONLY.httpStatus(), "SUNSET_CAPABILITY_READONLY");
        }
        AdminCommandResponse response = idempotencyService.execute(
                IDEMPOTENCY_SCOPE,
                idempotencyKey,
                requestHash(request),
                AdminCommandResponse.class,
                () -> acceptNewCommand(idempotencyKey, request));
        return ApiResult.ok(response);
    }

    private AdminCommandResponse acceptNewCommand(String idempotencyKey, AdminCommandRequest request) {
        String commandId = UUID.randomUUID().toString();
        boolean paramPersisted = false;
        if (StringUtils.hasText(request.paramKey())) {
            String configKey = "ops." + normalizeParamKey(request.paramKey());
            configFacade.upsertAdminValue(configKey, String.valueOf(request.paramValue()), "STRING", "admin_ops_state", request.reason().trim());
            paramPersisted = true;
        }
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(normalizeAction(request.action()))
                .resourceType(StringUtils.hasText(request.resourceType()) ? request.resourceType().trim() : "ADMIN_COMMAND")
                .resourceId(StringUtils.hasText(request.resourceId()) ? request.resourceId().trim() : commandId)
                .actorType("ADMIN")
                .actorUsername(StringUtils.hasText(request.operator()) ? request.operator().trim() : null)
                .result("SUCCESS")
                .riskLevel("MEDIUM")
                .detail(detail(idempotencyKey, commandId, request, paramPersisted))
                .build());
        return new AdminCommandResponse(
                commandId,
                request.action().trim(),
                StringUtils.hasText(request.resourceType()) ? request.resourceType().trim() : "ADMIN_COMMAND",
                StringUtils.hasText(request.resourceId()) ? request.resourceId().trim() : commandId,
                paramPersisted,
                LocalDateTime.now());
    }

    private ApiResult<AdminCommandResponse> requireCommand(String idempotencyKey, AdminCommandRequest request) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (request == null || !StringUtils.hasText(request.action())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "ACTION_REQUIRED");
        }
        if (!StringUtils.hasText(request.reason())) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        if (StringUtils.hasText(request.paramKey())) {
            normalizeParamKey(request.paramKey());
        }
        return null;
    }

    private String normalizeParamKey(String paramKey) {
        String normalized = paramKey.trim();
        if (!PARAM_KEY_PATTERN.matcher(normalized).matches()
                || normalized.contains("..")
                || normalized.toLowerCase(Locale.ROOT).startsWith("http")) {
            throw new IllegalArgumentException("PARAM_KEY_INVALID");
        }
        return normalized;
    }

    private String normalizeAction(String action) {
        return action.trim()
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_|_$", "");
    }

    private Map<String, Object> detail(String idempotencyKey, String commandId, AdminCommandRequest request, boolean paramPersisted) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("commandId", commandId);
        detail.put("domain", request.domain());
        detail.put("reason", request.reason().trim());
        detail.put("idempotencyKey", idempotencyKey.trim());
        detail.put("paramKey", request.paramKey());
        detail.put("paramValue", request.paramValue());
        detail.put("paramPersisted", paramPersisted);
        detail.put("payload", request.payload() == null ? Map.of() : request.payload());
        return detail;
    }

    private String requestHash(AdminCommandRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(objectMapper.writeValueAsString(request).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (JsonProcessingException ex) {
            throw new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "ADMIN_COMMAND_HASH_FAILED");
        } catch (NoSuchAlgorithmException ex) {
            throw new BizException(500, "SHA256_UNAVAILABLE");
        }
    }

    private boolean containsSunsetTerm(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return normalized.contains("premium") || normalized.contains("nexv2") || normalized.contains("nex.v2")
                || normalized.contains("points");
    }
}
