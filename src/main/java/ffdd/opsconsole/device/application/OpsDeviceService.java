package ffdd.opsconsole.device.application;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.device.domain.DeviceCatalogRepository;
import ffdd.opsconsole.device.domain.DeviceOrderView;
import ffdd.opsconsole.device.domain.DeviceOpsRepository;
import ffdd.opsconsole.device.domain.DeviceOpsView;
import ffdd.opsconsole.device.domain.DeviceReviewView;
import ffdd.opsconsole.device.domain.DeviceSkuView;
import ffdd.opsconsole.device.domain.DeviceTaskView;
import ffdd.opsconsole.device.domain.DeviceTradeinOverviewView;
import ffdd.opsconsole.device.dto.DatacenterOpsRequest;
import ffdd.opsconsole.device.dto.DeviceOrderActionRequest;
import ffdd.opsconsole.device.dto.DeviceOrderQueryRequest;
import ffdd.opsconsole.device.dto.DeviceOpsQueryRequest;
import ffdd.opsconsole.device.dto.DeviceReviewQueryRequest;
import ffdd.opsconsole.device.dto.DeviceReviewStatusRequest;
import ffdd.opsconsole.device.dto.DeviceReviewUpsertRequest;
import ffdd.opsconsole.device.dto.DeviceRestoreRequest;
import ffdd.opsconsole.device.dto.DeviceSkuQueryRequest;
import ffdd.opsconsole.device.dto.DeviceSkuStatusRequest;
import ffdd.opsconsole.device.dto.DeviceSkuUpsertRequest;
import ffdd.opsconsole.device.dto.DeviceTaskPriceRequest;
import ffdd.opsconsole.device.dto.DeviceTaskQueryRequest;
import ffdd.opsconsole.device.dto.DeviceTaskStatusRequest;
import ffdd.opsconsole.device.dto.DeviceTaskUpsertRequest;
import ffdd.opsconsole.device.dto.DeviceTradeinActionRequest;
import ffdd.opsconsole.device.dto.E3ConfigUpdateRequest;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsDeviceService {
    private static final Set<String> RESTORABLE_STATUSES = Set.of("RECYCLED", "DEACTIVATED", "INACTIVE", "RETIRED");
    private static final Set<String> SKU_STATUSES = Set.of("on", "off", "pending");
    private static final Set<String> SKU_TIERS = Set.of("Entry", "Pro", "Flagship", "Share");
    private static final Set<String> SKU_LIFECYCLES = Set.of("active", "legacy");
    private static final Set<String> SKU_UNLOCK_PHASES = Set.of("P1", "P2", "P3", "P4", "P5", "P6");
    private static final Set<String> REVIEW_STATUSES = Set.of("published", "hidden");
    private static final Set<String> TASK_STATUSES = Set.of("active", "paused", "inactive");
    private static final Set<String> TASK_UNITS = Set.of("/job", "/1k", "/min");
    private static final Set<String> TASK_REQUIREMENTS = Set.of("S1+", "需 NexionBox Pro", "需 NexionRack");
    private static final Set<String> ORDER_TERMINAL_STATES = Set.of("payment_failed", "expired", "refunded", "provisioning_failed");
    private static final Set<String> ORDER_FINAL_STATES = Set.of("active", "refunded", "cancelled", "payment_failed", "expired", "provisioning_failed");
    private static final Set<String> ORDER_CANCELABLE_STATES = Set.of("created", "paid");
    private static final Set<String> TRADEIN_OPERATIONS = Set.of("recycle", "replace", "deactivate");
    private static final String CURRENT_MONTH_KEY = "growth.phase.current_month";
    private static final String CURRENT_PHASE_KEY = "growth.phase.current";
    private static final String E1_GATE_GROUP = "device_e1_generation_gate";
    private static final String E1_GATE_PREFIX = "device.e1.generation.";
    private static final Pattern E1_GENERATION_ID = Pattern.compile("^[a-z0-9-]{1,80}$");
    private static final List<E1PhaseDef> E1_PHASES = List.of(
            new E1PhaseDef("P1", "L0+", "Entry · NexionBox S1"),
            new E1PhaseDef("P2", "L1+", "Genesis 节点"),
            new E1PhaseDef("P3", "L2+", "Pro v2 解锁"),
            new E1PhaseDef("P4", "L3+", "Cloud Share 池"),
            new E1PhaseDef("P5", "L4+", "Rack P2 解锁"),
            new E1PhaseDef("P6", "L6+", "Flagship · 顶配"));
    private static final List<E1GenerationReleaseDef> E1_RELEASES = List.of(
            new E1GenerationReleaseDef("stellarbox-pro-v2", "NexionBox Pro v2", 5, "P3", 300, true),
            new E1GenerationReleaseDef("stellarrack-p2", "NexionRack P2", 10, "P5", 800, false));
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
    private final DeviceCatalogRepository catalogRepository;
    private final PlatformConfigFacade configFacade;
    private final AuditLogService auditLogService;
    private final Clock clock;

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

    public ApiResult<List<DeviceOpsView>> userDevices(Long userId, int limit) {
        if (userId == null || userId <= 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "USER_ID_REQUIRED");
        }
        return ApiResult.ok(deviceRepository.listUserDevices(userId, Math.max(1, Math.min(200, limit))));
    }

    public ApiResult<PageResult<DeviceSkuView>> skus(DeviceSkuQueryRequest request) {
        return ApiResult.ok(catalogRepository.pageSkus(request));
    }

    public ApiResult<DeviceSkuView> sku(String skuId) {
        String normalized = normalizeId(skuId);
        if (!StringUtils.hasText(normalized)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SKU_ID_REQUIRED");
        }
        return catalogRepository.findSku(normalized)
                .map(ApiResult::ok)
                .orElseGet(() -> ApiResult.fail(404, "SKU_NOT_FOUND"));
    }

    public ApiResult<DeviceSkuView> createSku(String idempotencyKey, DeviceSkuUpsertRequest request) {
        ApiResult<DeviceSkuView> guard = requireSkuCommand(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        String skuId = normalizeSkuId(request.skuId(), request.name());
        if (catalogRepository.findSku(skuId).isPresent()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SKU_ALREADY_EXISTS");
        }
        DeviceSkuView created = catalogRepository.createSku(skuId, request, LocalDateTime.now(clock));
        audit("E1_SKU_CREATED", "DEVICE_SKU", created.skuId(), request.operator(), detail(
                "skuId", created.skuId(),
                "name", created.name(),
                "status", created.status(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(created);
    }

    public ApiResult<DeviceSkuView> updateSku(String skuId, String idempotencyKey, DeviceSkuUpsertRequest request) {
        ApiResult<DeviceSkuView> guard = requireSkuCommand(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        String normalized = normalizeId(skuId);
        if (!StringUtils.hasText(normalized)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SKU_ID_REQUIRED");
        }
        DeviceSkuView before = catalogRepository.findSku(normalized).orElse(null);
        if (before == null) {
            return ApiResult.fail(404, "SKU_NOT_FOUND");
        }
        DeviceSkuView updated = catalogRepository.updateSku(normalized, request, LocalDateTime.now(clock)).orElse(before);
        audit("E1_SKU_UPDATED", "DEVICE_SKU", normalized, request.operator(), detail(
                "skuId", normalized,
                "beforeName", before.name(),
                "afterName", updated.name(),
                "beforePrice", before.price(),
                "afterPrice", updated.price(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    public ApiResult<DeviceSkuView> updateSkuStatus(String skuId, String idempotencyKey, DeviceSkuStatusRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return ApiResult.fail(guard.getCode(), guard.getMessage());
        }
        String status = normalizeSkuStatus(request.status());
        if (status == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SKU_STATUS_INVALID");
        }
        String normalized = normalizeId(skuId);
        DeviceSkuView before = catalogRepository.findSku(normalized).orElse(null);
        if (before == null) {
            return ApiResult.fail(404, "SKU_NOT_FOUND");
        }
        if (status.equals(before.status())) {
            return ApiResult.ok(before);
        }
        DeviceSkuView updated = catalogRepository.updateSkuStatus(normalized, status, LocalDateTime.now(clock)).orElse(before);
        audit("E1_SKU_STATUS_CHANGED", "DEVICE_SKU", normalized, request.operator(), detail(
                "skuId", normalized,
                "fromStatus", before.status(),
                "toStatus", status,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    public ApiResult<Map<String, Object>> deleteSku(String skuId, String idempotencyKey, DeviceSkuStatusRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String normalized = normalizeId(skuId);
        DeviceSkuView before = catalogRepository.findSku(normalized).orElse(null);
        if (before == null) {
            return ApiResult.fail(404, "SKU_NOT_FOUND");
        }
        catalogRepository.softDeleteSku(normalized, LocalDateTime.now(clock));
        audit("E1_SKU_DELETED", "DEVICE_SKU", normalized, request.operator(), detail(
                "skuId", normalized,
                "name", before.name(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(detail("skuId", normalized, "deleted", true));
    }

    public ApiResult<PageResult<DeviceReviewView>> reviews(DeviceReviewQueryRequest request) {
        return ApiResult.ok(catalogRepository.pageReviews(request));
    }

    public ApiResult<DeviceReviewView> createReview(String idempotencyKey, DeviceReviewUpsertRequest request) {
        ApiResult<DeviceReviewView> guard = requireReviewCommand(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        String reviewId = nextReviewId();
        DeviceReviewView created = catalogRepository.createReview(reviewId, request, LocalDateTime.now(clock));
        audit("E1_REVIEW_CREATED", "DEVICE_REVIEW", created.reviewId(), request.operator(), detail(
                "reviewId", created.reviewId(),
                "skuId", created.skuId(),
                "status", created.status(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(created);
    }

    public ApiResult<DeviceReviewView> updateReview(String reviewId, String idempotencyKey, DeviceReviewUpsertRequest request) {
        ApiResult<DeviceReviewView> guard = requireReviewCommand(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        String normalized = normalizeId(reviewId);
        DeviceReviewView before = catalogRepository.findReview(normalized).orElse(null);
        if (before == null) {
            return ApiResult.fail(404, "REVIEW_NOT_FOUND");
        }
        DeviceReviewView updated = catalogRepository.updateReview(normalized, request, LocalDateTime.now(clock)).orElse(before);
        audit("E1_REVIEW_UPDATED", "DEVICE_REVIEW", normalized, request.operator(), detail(
                "reviewId", normalized,
                "skuId", updated.skuId(),
                "beforeStatus", before.status(),
                "afterStatus", updated.status(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    public ApiResult<DeviceReviewView> updateReviewStatus(String reviewId, String idempotencyKey, DeviceReviewStatusRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return ApiResult.fail(guard.getCode(), guard.getMessage());
        }
        String status = normalizeReviewStatus(request.status());
        if (status == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "REVIEW_STATUS_INVALID");
        }
        String normalized = normalizeId(reviewId);
        DeviceReviewView before = catalogRepository.findReview(normalized).orElse(null);
        if (before == null) {
            return ApiResult.fail(404, "REVIEW_NOT_FOUND");
        }
        if (status.equals(before.status())) {
            return ApiResult.ok(before);
        }
        DeviceReviewView updated = catalogRepository.updateReviewStatus(normalized, status, LocalDateTime.now(clock)).orElse(before);
        audit("E1_REVIEW_STATUS_CHANGED", "DEVICE_REVIEW", normalized, request.operator(), detail(
                "reviewId", normalized,
                "fromStatus", before.status(),
                "toStatus", status,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    public ApiResult<Map<String, Object>> deleteReview(String reviewId, String idempotencyKey, DeviceReviewStatusRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String normalized = normalizeId(reviewId);
        DeviceReviewView before = catalogRepository.findReview(normalized).orElse(null);
        if (before == null) {
            return ApiResult.fail(404, "REVIEW_NOT_FOUND");
        }
        catalogRepository.softDeleteReview(normalized, LocalDateTime.now(clock));
        audit("E1_REVIEW_DELETED", "DEVICE_REVIEW", normalized, request.operator(), detail(
                "reviewId", normalized,
                "skuId", before.skuId(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(detail("reviewId", normalized, "deleted", true));
    }

    public ApiResult<PageResult<DeviceTaskView>> tasks(DeviceTaskQueryRequest request) {
        return ApiResult.ok(catalogRepository.pageTasks(request));
    }

    public ApiResult<DeviceTaskView> createTask(String idempotencyKey, DeviceTaskUpsertRequest request) {
        ApiResult<DeviceTaskView> guard = requireTaskCommand(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        String taskId = nextTaskId();
        DeviceTaskView created = catalogRepository.createTask(taskId, request, LocalDateTime.now(clock));
        audit("E2_TASK_CREATED", "DEVICE_TASK", created.taskId(), request.operator(), detail(
                "taskId", created.taskId(),
                "name", created.name(),
                "price", created.price(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(created);
    }

    public ApiResult<DeviceTaskView> updateTaskPrice(String taskId, String idempotencyKey, DeviceTaskPriceRequest request) {
        ApiResult<Map<String, Object>> command = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (command != null) {
            return ApiResult.fail(command.getCode(), command.getMessage());
        }
        if (request.price() == null || request.price().compareTo(BigDecimal.ZERO) <= 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TASK_PRICE_INVALID");
        }
        String normalized = normalizeId(taskId);
        DeviceTaskView before = catalogRepository.findTask(normalized).orElse(null);
        if (before == null) {
            return ApiResult.fail(404, "TASK_NOT_FOUND");
        }
        DeviceTaskView updated = catalogRepository.updateTaskPrice(normalized, request.price(), LocalDateTime.now(clock)).orElse(before);
        audit("E2_TASK_PRICE_CHANGED", "DEVICE_TASK", normalized, request.operator(), detail(
                "taskId", normalized,
                "beforePrice", before.price(),
                "afterPrice", updated.price(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    public ApiResult<DeviceTaskView> updateTaskStatus(String taskId, String idempotencyKey, DeviceTaskStatusRequest request) {
        ApiResult<Map<String, Object>> command = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (command != null) {
            return ApiResult.fail(command.getCode(), command.getMessage());
        }
        String status = normalizeTaskStatus(request.status());
        if (status == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TASK_STATUS_INVALID");
        }
        String normalized = normalizeId(taskId);
        DeviceTaskView before = catalogRepository.findTask(normalized).orElse(null);
        if (before == null) {
            return ApiResult.fail(404, "TASK_NOT_FOUND");
        }
        DeviceTaskView updated = catalogRepository.updateTaskStatus(normalized, status, LocalDateTime.now(clock)).orElse(before);
        audit("E2_TASK_STATUS_CHANGED", "DEVICE_TASK", normalized, request.operator(), detail(
                "taskId", normalized,
                "fromStatus", before.status(),
                "toStatus", updated.status(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    public ApiResult<Map<String, Object>> deleteTask(String taskId, String idempotencyKey, DeviceTaskStatusRequest request) {
        ApiResult<Map<String, Object>> command = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (command != null) {
            return command;
        }
        String normalized = normalizeId(taskId);
        DeviceTaskView before = catalogRepository.findTask(normalized).orElse(null);
        if (before == null) {
            return ApiResult.fail(404, "TASK_NOT_FOUND");
        }
        catalogRepository.softDeleteTask(normalized, LocalDateTime.now(clock));
        audit("E2_TASK_DELETED", "DEVICE_TASK", normalized, request.operator(), detail(
                "taskId", normalized,
                "name", before.name(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(detail("taskId", normalized, "deleted", true));
    }

    public ApiResult<PageResult<DeviceOrderView>> orders(DeviceOrderQueryRequest request) {
        return ApiResult.ok(catalogRepository.pageOrders(request));
    }

    public ApiResult<DeviceOrderView> refundOrder(String orderNo, String idempotencyKey, DeviceOrderActionRequest request) {
        return transitionOrder(orderNo, idempotencyKey, request, "refunded", "E4_ORDER_REFUNDED");
    }

    public ApiResult<DeviceOrderView> cancelOrder(String orderNo, String idempotencyKey, DeviceOrderActionRequest request) {
        return transitionOrder(orderNo, idempotencyKey, request, "cancelled", "E4_ORDER_CANCELLED");
    }

    public ApiResult<DeviceOrderView> terminalOrder(String orderNo, String idempotencyKey, DeviceOrderActionRequest request) {
        ApiResult<Map<String, Object>> command = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (command != null) {
            return ApiResult.fail(command.getCode(), command.getMessage());
        }
        String terminal = normalizeOrderState(request.terminalState());
        if (!ORDER_TERMINAL_STATES.contains(terminal)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "ORDER_TERMINAL_STATE_INVALID");
        }
        return transitionOrder(orderNo, idempotencyKey, request, terminal, "E4_ORDER_TERMINALIZED");
    }

    public ApiResult<Map<String, Object>> e1GenerationGates() {
        int platformMonth = currentPlatformMonth();
        Map<String, String> configValues = e1GateConfigValues();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "E1");
        response.put("phaseOrder", E1_PHASES.stream().map(E1PhaseDef::p).toList());
        response.put("phases", E1_PHASES);
        response.put("platformMonth", platformMonth);
        response.put("phaseCurrent", configFacade.activeValue(CURRENT_PHASE_KEY).orElse(phaseForMonth(platformMonth)));
        response.put("releases", e1GenerationReleases(configValues));
        response.put("configValues", configValues);
        response.put("allowedFields", List.of("phaseOffset", "forceUnlock"));
        response.put("sources", List.of("nx_config_item:" + E1_GATE_GROUP, "H1 current phase", "E5 eligibility"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updateE1GenerationGate(String idempotencyKey, E3ConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String[] key = normalizeE1GateKey(request.key());
        String value = normalizeE1GateValue(key[1], request.value());
        String configKey = E1_GATE_PREFIX + key[0] + "." + key[1];
        String oldValue = configFacade.activeValue(configKey).orElse(null);
        configFacade.upsertAdminValue(configKey, value, "phaseOffset".equals(key[1]) ? "NUMBER" : "BOOLEAN", E1_GATE_GROUP, request.reason().trim());
        audit("E1_GENERATION_GATE_CHANGED", "DEVICE_GENERATION_GATE", key[0], request.operator(), detail(
                "generationId", key[0],
                "field", key[1],
                "oldValue", oldValue,
                "newValue", value,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return e1GenerationGates();
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

    public ApiResult<DeviceTradeinOverviewView> e3TradeinOverview() {
        Map<String, String> config = deviceRepository.e3Config();
        int cliffMonth = parsePositiveInt(config.get("stageMidEnd"), 8) + 1;
        LocalDateTime now = LocalDateTime.now(clock);
        return ApiResult.ok(deviceRepository.e3TradeinOverview(
                now.minusHours(24),
                now.toLocalDate().withDayOfMonth(1).atStartOfDay(),
                cliffMonth));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<DeviceOpsView> executeTradeinAction(
            String operation,
            String idempotencyKey,
            DeviceTradeinActionRequest request) {
        ApiResult<DeviceOpsView> guard = requireTradeinCommand(operation, idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        String normalizedOperation = normalizeTradeinOperation(operation);
        LocalDateTime now = LocalDateTime.now(clock);
        String tradeInNo = "TI-" + now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + "-" + request.deviceId();
        DeviceOpsView updated = deviceRepository.executeTradeinAction(normalizedOperation, request.deviceId(), tradeInNo, now).orElse(null);
        if (updated == null) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        audit("E3_TRADEIN_" + normalizedOperation.toUpperCase(Locale.ROOT), "DEVICE", String.valueOf(request.deviceId()), request.operator(), detail(
                "operation", normalizedOperation,
                "deviceId", request.deviceId(),
                "instanceNo", updated.instanceNo(),
                "toStatus", updated.status(),
                "tradeInNo", "replace".equals(normalizedOperation) ? tradeInNo : null,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
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

    private ApiResult<DeviceSkuView> requireSkuCommand(String idempotencyKey, DeviceSkuUpsertRequest request) {
        ApiResult<Map<String, Object>> command = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (command != null) {
            return ApiResult.fail(command.getCode(), command.getMessage());
        }
        if (request == null || !StringUtils.hasText(request.name())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SKU_NAME_REQUIRED");
        }
        if (request.price() == null || request.price().compareTo(BigDecimal.ZERO) <= 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SKU_PRICE_REQUIRED");
        }
        String tier = normalizeExact(request.tier());
        if (!SKU_TIERS.contains(tier)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SKU_TIER_INVALID");
        }
        if (!StringUtils.hasText(request.datacenter())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SKU_DATACENTER_REQUIRED");
        }
        if (request.generation() == null || request.generation() < 1 || request.generation() > 3) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SKU_GENERATION_INVALID");
        }
        if (!allows(request.lifecycle(), SKU_LIFECYCLES)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SKU_LIFECYCLE_INVALID");
        }
        if (!"Share".equals(tier) && !allows(request.unlockPhase(), SKU_UNLOCK_PHASES)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SKU_UNLOCK_PHASE_INVALID");
        }
        if (normalizeSkuStatus(request.status()) == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SKU_STATUS_INVALID");
        }
        return null;
    }

    private ApiResult<DeviceReviewView> requireReviewCommand(String idempotencyKey, DeviceReviewUpsertRequest request) {
        ApiResult<Map<String, Object>> command = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (command != null) {
            return ApiResult.fail(command.getCode(), command.getMessage());
        }
        if (request == null || !StringUtils.hasText(request.skuId())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "REVIEW_SKU_REQUIRED");
        }
        if (catalogRepository.findSku(request.skuId().trim()).isEmpty()) {
            return ApiResult.fail(404, "SKU_NOT_FOUND");
        }
        if (!StringUtils.hasText(request.author())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "REVIEW_AUTHOR_REQUIRED");
        }
        if (!StringUtils.hasText(request.content())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "REVIEW_CONTENT_REQUIRED");
        }
        if (request.rating() == null || request.rating() < 1 || request.rating() > 5) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "REVIEW_RATING_INVALID");
        }
        if (normalizeReviewStatus(request.status()) == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "REVIEW_STATUS_INVALID");
        }
        return null;
    }

    private ApiResult<DeviceTaskView> requireTaskCommand(String idempotencyKey, DeviceTaskUpsertRequest request) {
        ApiResult<Map<String, Object>> command = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (command != null) {
            return ApiResult.fail(command.getCode(), command.getMessage());
        }
        if (request == null || !StringUtils.hasText(request.name())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TASK_NAME_REQUIRED");
        }
        if (request.price() == null || request.price().compareTo(BigDecimal.ZERO) <= 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TASK_PRICE_INVALID");
        }
        if (!allows(request.unit(), TASK_UNITS)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TASK_UNIT_INVALID");
        }
        if (!allows(request.requirement(), TASK_REQUIREMENTS)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TASK_REQUIREMENT_INVALID");
        }
        if (request.saturation() != null
                && (request.saturation().compareTo(BigDecimal.ZERO) < 0 || request.saturation().compareTo(BigDecimal.ONE) > 0)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TASK_SATURATION_INVALID");
        }
        if (normalizeTaskStatus(request.status()) == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TASK_STATUS_INVALID");
        }
        return null;
    }

    private ApiResult<DeviceOrderView> transitionOrder(
            String orderNo,
            String idempotencyKey,
            DeviceOrderActionRequest request,
            String toState,
            String auditAction) {
        ApiResult<Map<String, Object>> command = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (command != null) {
            return ApiResult.fail(command.getCode(), command.getMessage());
        }
        String normalizedOrderNo = normalizeId(orderNo);
        DeviceOrderView before = catalogRepository.findOrder(normalizedOrderNo).orElse(null);
        if (before == null) {
            return ApiResult.fail(404, "ORDER_NOT_FOUND");
        }
        String fromState = normalizeOrderState(before.state());
        if (toState.equals(fromState)) {
            return ApiResult.ok(before);
        }
        if (!canMoveOrder(fromState, toState)) {
            return ApiResult.fail(
                    OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(),
                    OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        DeviceOrderView updated = catalogRepository.updateOrderState(normalizedOrderNo, toState, LocalDateTime.now(clock)).orElse(before);
        audit(auditAction, "DEVICE_ORDER", normalizedOrderNo, request.operator(), detail(
                "orderNo", normalizedOrderNo,
                "fromState", fromState,
                "toState", toState,
                "amount", before.amount(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    private boolean canMoveOrder(String fromState, String toState) {
        if ("cancelled".equals(toState)) {
            return ORDER_CANCELABLE_STATES.contains(fromState);
        }
        if ("refunded".equals(toState)) {
            return !ORDER_FINAL_STATES.contains(fromState) || "failed".equals(fromState);
        }
        if (ORDER_TERMINAL_STATES.contains(toState)) {
            return !ORDER_FINAL_STATES.contains(fromState);
        }
        return false;
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

    private ApiResult<DeviceOpsView> requireTradeinCommand(String operation, String idempotencyKey, DeviceTradeinActionRequest request) {
        if (normalizeTradeinOperation(operation) == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TRADEIN_OPERATION_INVALID");
        }
        if (request == null || request.deviceId() == null || request.deviceId() < 1) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DEVICE_ID_REQUIRED");
        }
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (!StringUtils.hasText(request.reason())) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private Map<String, String> e1GateConfigValues() {
        Map<String, String> response = new LinkedHashMap<>();
        configFacade.activeValuesByGroup(E1_GATE_GROUP).forEach((key, value) -> {
            if (!key.startsWith(E1_GATE_PREFIX)) {
                return;
            }
            String suffix = key.substring(E1_GATE_PREFIX.length());
            int split = suffix.lastIndexOf('.');
            if (split <= 0 || split >= suffix.length() - 1) {
                return;
            }
            String generationId = suffix.substring(0, split);
            String field = suffix.substring(split + 1);
            if (E1_GENERATION_ID.matcher(generationId).matches() && Set.of("phaseOffset", "forceUnlock").contains(field)) {
                response.put("E.gen." + generationId + "." + field, value);
            }
        });
        return response;
    }

    private List<Map<String, Object>> e1GenerationReleases(Map<String, String> configValues) {
        return E1_RELEASES.stream()
                .map(release -> {
                    int releaseMonth = readInt(E1_GATE_PREFIX + release.id() + ".releaseMonth", release.releaseMonth());
                    int discount = readInt(E1_GATE_PREFIX + release.id() + ".discount", release.discount());
                    boolean eligibility = readBoolean(E1_GATE_PREFIX + release.id() + ".eligibility", release.eligibility());
                    int phaseOffset = parseInt(configValues.get("E.gen." + release.id() + ".phaseOffset"), 0);
                    boolean forceUnlock = parseBoolean(configValues.get("E.gen." + release.id() + ".forceUnlock"), false);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", release.id());
                    row.put("name", release.name());
                    row.put("releaseMonth", releaseMonth);
                    row.put("phase", release.phase());
                    row.put("discount", discount);
                    row.put("eligibility", eligibility);
                    row.put("phaseOffset", phaseOffset);
                    row.put("forceUnlock", forceUnlock);
                    row.put("effectiveReleaseMonth", releaseMonth + phaseOffset);
                    return row;
                })
                .toList();
    }

    private int currentPlatformMonth() {
        int month = readInt(CURRENT_MONTH_KEY, 4);
        return month >= 1 && month <= 12 ? month : 4;
    }

    private String phaseForMonth(int month) {
        if (month <= 2) {
            return "P1";
        }
        if (month <= 4) {
            return "P2";
        }
        if (month <= 7) {
            return "P3";
        }
        if (month == 8) {
            return "P4";
        }
        if (month <= 10) {
            return "P5";
        }
        return "P6";
    }

    private int readInt(String configKey, int fallback) {
        return parseInt(configFacade.activeValue(configKey).orElse(null), fallback);
    }

    private int parseInt(String value, int fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim().replace("M", ""));
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private boolean readBoolean(String configKey, boolean fallback) {
        return parseBoolean(configFacade.activeValue(configKey).orElse(null), fallback);
    }

    private boolean parseBoolean(String value, boolean fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized)) {
            return false;
        }
        return fallback;
    }

    private String[] normalizeE1GateKey(String rawKey) {
        String key = normalizeId(rawKey);
        if (key.startsWith("E.gen.")) {
            key = key.substring("E.gen.".length());
        }
        int split = key.lastIndexOf('.');
        if (split <= 0 || split >= key.length() - 1) {
            throw new IllegalArgumentException("E1_GATE_KEY_INVALID");
        }
        String generationId = key.substring(0, split);
        String field = key.substring(split + 1);
        if (!E1_GENERATION_ID.matcher(generationId).matches() || !Set.of("phaseOffset", "forceUnlock").contains(field)) {
            throw new IllegalArgumentException("E1_GATE_KEY_INVALID");
        }
        return new String[] { generationId, field };
    }

    private String normalizeE1GateValue(String field, String rawValue) {
        if ("phaseOffset".equals(field)) {
            int offset = parsePositiveOrNegativeInt(rawValue);
            if (offset < -12 || offset > 12) {
                throw new IllegalArgumentException("E1_GATE_PHASE_OFFSET_OUT_OF_RANGE");
            }
            return String.valueOf(offset);
        }
        if (!StringUtils.hasText(rawValue)) {
            throw new IllegalArgumentException("E1_GATE_VALUE_REQUIRED");
        }
        String value = rawValue.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("true", "false").contains(value)) {
            throw new IllegalArgumentException("E1_GATE_BOOLEAN_INVALID");
        }
        return value;
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
        if (key.startsWith("degrade")) {
            if (value.compareTo(BigDecimal.valueOf(-100)) < 0 || value.compareTo(BigDecimal.ZERO) > 0) {
                throw new IllegalArgumentException("degrade config must be -100-0");
            }
            return value.setScale(2, RoundingMode.HALF_UP);
        }
        if ("minEfficiency".equals(key) || "salvagePct".equals(key)) {
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

    private String normalizeSkuId(String skuId, String name) {
        String candidate = StringUtils.hasText(skuId) ? skuId : name;
        String normalized = candidate == null ? "" : candidate.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (!StringUtils.hasText(normalized)) {
            throw new IllegalArgumentException("skuId is required");
        }
        return normalized;
    }

    private String normalizeId(String id) {
        return StringUtils.hasText(id) ? id.trim() : "";
    }

    private String normalizeSkuStatus(String status) {
        String normalized = status == null ? "pending" : status.trim().toLowerCase(Locale.ROOT);
        return SKU_STATUSES.contains(normalized) ? normalized : null;
    }

    private String normalizeReviewStatus(String status) {
        String normalized = status == null ? "published" : status.trim().toLowerCase(Locale.ROOT);
        return REVIEW_STATUSES.contains(normalized) ? normalized : null;
    }

    private String normalizeTaskStatus(String status) {
        String normalized = status == null ? "active" : status.trim().toLowerCase(Locale.ROOT);
        return TASK_STATUSES.contains(normalized) ? normalized : null;
    }

    private boolean allows(String value, Set<String> allowed) {
        return StringUtils.hasText(value) && allowed.contains(value.trim());
    }

    private String normalizeExact(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    private String normalizeOrderState(String state) {
        return StringUtils.hasText(state) ? state.trim().toLowerCase(Locale.ROOT) : "";
    }

    private String normalizeTradeinOperation(String operation) {
        String normalized = StringUtils.hasText(operation) ? operation.trim().toLowerCase(Locale.ROOT) : "";
        return TRADEIN_OPERATIONS.contains(normalized) ? normalized : null;
    }

    private int parsePositiveInt(String raw, int fallback) {
        if (!StringUtils.hasText(raw)) {
            return fallback;
        }
        try {
            return Math.max(0, Integer.parseInt(raw.trim().replaceAll("[^0-9-]", "")));
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private int parsePositiveOrNegativeInt(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new IllegalArgumentException("numeric config is required");
        }
        try {
            return Integer.parseInt(raw.trim().replace("M", ""));
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("numeric config is invalid", ex);
        }
    }

    private String nextTaskId() {
        String base = "TK-" + clock.millis();
        String candidate = base;
        int i = 1;
        while (catalogRepository.findTask(candidate).isPresent()) {
            candidate = base + "-" + i++;
        }
        return candidate;
    }

    private String nextReviewId() {
        String base = "rv-" + clock.millis();
        String candidate = base;
        int i = 1;
        while (catalogRepository.findReview(candidate).isPresent()) {
            candidate = base + "-" + i++;
        }
        return candidate;
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

    private Map<String, Object> detail(Object... values) {
        Map<String, Object> detail = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            if (values[i] != null) {
                detail.put(String.valueOf(values[i]), values[i + 1]);
            }
        }
        return detail;
    }

    private record E1PhaseDef(String p, String meta, String skus) {
    }

    private record E1GenerationReleaseDef(
            String id,
            String name,
            int releaseMonth,
            String phase,
            int discount,
            boolean eligibility) {
    }
}
