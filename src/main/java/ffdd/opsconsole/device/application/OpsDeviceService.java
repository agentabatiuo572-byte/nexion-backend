package ffdd.opsconsole.device.application;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.device.domain.DeviceOpsRepository;
import ffdd.opsconsole.device.domain.DeviceOpsView;
import ffdd.opsconsole.device.dto.DatacenterOpsRequest;
import ffdd.opsconsole.device.dto.DeviceOpsQueryRequest;
import ffdd.opsconsole.device.dto.DeviceRestoreRequest;
import ffdd.opsconsole.device.dto.E3ConfigUpdateRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.util.StringUtils;

@ApplicationService
public class OpsDeviceService {
    private static final Set<String> RESTORABLE_STATUSES = Set.of("RECYCLED", "DEACTIVATED", "INACTIVE", "RETIRED");
    private static final Set<String> E3_CONFIG_KEYS = Set.of(
            "degradeEarly",
            "degradeMid",
            "degradeLate",
            "stageEarlyEnd",
            "stageMidEnd",
            "cycleMonths",
            "minEfficiency",
            "salvagePct",
            "minHoldingMonths",
            "promoMult",
            "promoCooldownDays",
            "promoMaxPerSession",
            "promoDelaySeconds",
            "promoMinAgeDays",
            "inventorySoftMax");

    private final DeviceOpsRepository deviceRepository;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public OpsDeviceService(DeviceOpsRepository deviceRepository, AuditLogService auditLogService) {
        this(deviceRepository, auditLogService, Clock.systemDefaultZone());
    }

    OpsDeviceService(DeviceOpsRepository deviceRepository, AuditLogService auditLogService, Clock clock) {
        this.deviceRepository = deviceRepository;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    public ApiResult<Map<String, Object>> overview() {
        Map<String, Object> response = new LinkedHashMap<>(deviceRepository.overviewCounters());
        response.put("domain", "E");
        response.put("service", "nexion-backend");
        response.put("generatedAt", LocalDateTime.now(clock));
        response.put("sources", List.of("nx_user_device", "nx_user_device_runtime", "nx_compute_dc_ops_state"));
        return ApiResult.ok(response);
    }

    public ApiResult<PageResult<DeviceOpsView>> devices(DeviceOpsQueryRequest request) {
        return ApiResult.ok(deviceRepository.pageDevices(request));
    }

    public ApiResult<Map<String, Object>> e3Overview() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "E3");
        response.put("config", deviceRepository.e3Config());
        response.put("restoreEndpoint", "POST /api/admin/devices/{id}/restore");
        response.put("sources", List.of("nx_device_lifecycle_rule", "nx_compute_e3_config", "nx_tradein_rule", "nx_user_device"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updateE3Config(String idempotencyKey, E3ConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String key = normalizeE3Key(request.key());
        BigDecimal value = normalizeE3Value(key, request.value());
        Map<String, String> before = deviceRepository.e3Config();
        deviceRepository.upsertE3Config(key, value.stripTrailingZeros().toPlainString(), "NUMBER", operator(request.operator()));
        Map<String, String> after = deviceRepository.e3Config();
        audit("E3_CONFIG_CHANGED", "DEVICE_E3_CONFIG", key, request.operator(), Map.of(
                "key", key,
                "oldValue", before.get(key),
                "newValue", after.get(key),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return e3Overview();
    }

    public ApiResult<DeviceOpsView> restoreDevice(Long deviceId, String idempotencyKey, DeviceRestoreRequest request) {
        ApiResult<DeviceOpsView> guard = requireDeviceCommand(deviceId, idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        DeviceOpsView device = deviceRepository.findDevice(deviceId).orElse(null);
        if (device == null) {
            return ApiResult.fail(404, "DEVICE_NOT_FOUND");
        }
        if (!canRestore(device)) {
            return ApiResult.fail(
                    OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(),
                    OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        DeviceOpsView restored = deviceRepository.restoreDevice(deviceId, LocalDateTime.now(clock)).orElse(device);
        audit("E3B_DEVICE_RESTORE", "DEVICE", String.valueOf(deviceId), request.operator(), Map.of(
                "fromStatus", device.status(),
                "toStatus", "OFFLINE",
                "instanceNo", device.instanceNo(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(restored);
    }

    public ApiResult<Map<String, Object>> pauseDatacenter(String dcLocation, String idempotencyKey, DatacenterOpsRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String dc = normalizeDc(dcLocation);
        LocalDateTime now = LocalDateTime.now(clock);
        deviceRepository.pauseDatacenter(dc, request.reason().trim(), operator(request.operator()), now);
        audit("E5_DATACENTER_PAUSED", "DEVICE_DATACENTER", dc, request.operator(), Map.of(
                "dcLocation", dc,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return overview();
    }

    public ApiResult<Map<String, Object>> resumeDatacenter(String dcLocation, String idempotencyKey, DatacenterOpsRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String dc = normalizeDc(dcLocation);
        deviceRepository.resumeDatacenter(dc, operator(request.operator()), LocalDateTime.now(clock));
        audit("E5_DATACENTER_RESUMED", "DEVICE_DATACENTER", dc, request.operator(), Map.of(
                "dcLocation", dc,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return overview();
    }

    private boolean canRestore(DeviceOpsView device) {
        String status = device.status() == null ? "" : device.status().trim().toUpperCase(Locale.ROOT);
        return RESTORABLE_STATUSES.contains(status)
                || Integer.valueOf(1).equals(device.pendingDeactivate())
                || device.deactivatedAt() != null;
    }

    private ApiResult<Map<String, Object>> requireCommand(String idempotencyKey, String reason) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (!StringUtils.hasText(reason)) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private ApiResult<DeviceOpsView> requireDeviceCommand(Long deviceId, String idempotencyKey, DeviceRestoreRequest request) {
        if (deviceId == null || deviceId < 1) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DEVICE_ID_REQUIRED");
        }
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (request == null || !StringUtils.hasText(request.reason())) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private String normalizeE3Key(String key) {
        String normalized = key == null ? "" : key.trim();
        if (normalized.startsWith("E.device.")) {
            normalized = normalized.substring("E.device.".length());
        } else if (normalized.startsWith("E.tradein.")) {
            normalized = normalized.substring("E.tradein.".length());
        }
        normalized = normalized.replace("promo.", "promo");
        if (!E3_CONFIG_KEYS.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported E3 config key");
        }
        return normalized;
    }

    private BigDecimal normalizeE3Value(String key, String raw) {
        BigDecimal value = parseDecimal(raw);
        if (key.startsWith("degrade") || "minEfficiency".equals(key) || "salvagePct".equals(key)) {
            if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new IllegalArgumentException("percent config must be 0-100");
            }
            return value.setScale(2, RoundingMode.HALF_UP);
        }
        if (key.endsWith("Days") || key.endsWith("Seconds") || key.endsWith("Max") || key.endsWith("Months")
                || "stageEarlyEnd".equals(key) || "stageMidEnd".equals(key) || "cycleMonths".equals(key)
                || "inventorySoftMax".equals(key)) {
            if (value.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("numeric config must be positive");
            }
            return BigDecimal.valueOf(value.setScale(0, RoundingMode.DOWN).longValue());
        }
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal parseDecimal(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new IllegalArgumentException("value is required");
        }
        try {
            return new BigDecimal(raw.trim().replace("%", "").replace(",", ""));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("value is invalid", ex);
        }
    }

    private String normalizeDc(String dcLocation) {
        if (!StringUtils.hasText(dcLocation)) {
            throw new IllegalArgumentException("dcLocation is required");
        }
        return dcLocation.trim();
    }

    private String operator(String operator) {
        return StringUtils.hasText(operator) ? operator.trim() : "system";
    }

    private void audit(String action, String resourceType, String resourceId, String operator, Map<String, Object> detail) {
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .bizNo(resourceId)
                .actorType("ADMIN")
                .actorUsername(operator(operator))
                .result("SUCCESS")
                .riskLevel("HIGH")
                .detail(detail)
                .build());
    }
}
