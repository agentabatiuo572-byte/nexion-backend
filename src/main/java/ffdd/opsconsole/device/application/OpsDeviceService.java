package ffdd.opsconsole.device.application;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.device.domain.ComputeConfigRegistry;
import ffdd.opsconsole.device.domain.ComputeConfigView;
import ffdd.opsconsole.device.domain.DeviceCatalogRepository;
import ffdd.opsconsole.device.domain.DeviceDatacenterView;
import ffdd.opsconsole.device.domain.DeviceGenerationGateView;
import ffdd.opsconsole.device.domain.DeviceOrderView;
import ffdd.opsconsole.device.domain.DeviceOpsRepository;
import ffdd.opsconsole.device.domain.DeviceOpsView;
import ffdd.opsconsole.device.domain.DevicePhaseView;
import ffdd.opsconsole.device.domain.DevicePhoneTierRewardView;
import ffdd.opsconsole.device.domain.DevicePurchaseGateView;
import ffdd.opsconsole.device.domain.DeviceReviewView;
import ffdd.opsconsole.device.domain.DeviceSkuView;
import ffdd.opsconsole.device.domain.DeviceTaskView;
import ffdd.opsconsole.device.domain.DeviceTradeinOverviewView;
import ffdd.opsconsole.device.dto.ComputeConfigParamResponse;
import ffdd.opsconsole.device.dto.ComputeConfigParamUpdateRequest;
import ffdd.opsconsole.device.dto.DatacenterOpsRequest;
import ffdd.opsconsole.device.dto.DeviceDatacenterUpsertRequest;
import ffdd.opsconsole.device.dto.DeviceGenerationGateArchiveRequest;
import ffdd.opsconsole.device.dto.DeviceGenerationGatePatchRequest;
import ffdd.opsconsole.device.dto.DeviceGenerationGateUpsertRequest;
import ffdd.opsconsole.device.dto.DeviceOrderActionRequest;
import ffdd.opsconsole.device.dto.DeviceOrderQueryRequest;
import ffdd.opsconsole.device.dto.DeviceOrderStateRequest;
import ffdd.opsconsole.device.dto.DevicePhaseArchiveRequest;
import ffdd.opsconsole.device.dto.DevicePhaseCurrentRequest;
import ffdd.opsconsole.device.dto.DevicePhaseUpsertRequest;
import ffdd.opsconsole.device.dto.DevicePhoneTierRewardUpdateRequest;
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
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
    private static final Set<String> REVIEW_STATUSES = Set.of("published", "hidden");
    private static final Set<String> TASK_STATUSES = Set.of("active", "paused", "inactive");
    private static final Set<String> TASK_UNITS = Set.of("/job", "/1k", "/min");
    private static final Set<String> TASK_REQUIREMENTS = Set.of("手机+", "S1+", "需 NexionBox Pro", "需 NexionRack");
    private static final Set<String> TASK_CLASSES = Set.of("llm-inference", "image-gen", "video-render", "fine-tune", "embedding");
    private static final Set<String> TASK_KILL_INIT_STATES = Set.of("派发中", "已 kill", "限流中");
    private static final Set<String> DATACENTER_STATUSES = Set.of("active", "maintenance", "disabled");
    private static final Set<String> ORDER_TERMINAL_STATES = Set.of("payment_failed", "expired", "refunded", "provisioning_failed");
    private static final Set<String> ORDER_FINAL_STATES = Set.of("active", "refunded", "cancelled", "payment_failed", "expired", "provisioning_failed");
    private static final Set<String> ORDER_CANCELABLE_STATES = Set.of("created", "paid");
    private static final List<String> ORDER_MAIN_FLOW = List.of("created", "paid", "allocating", "active");
    private static final Set<String> TRADEIN_OPERATIONS = Set.of("recycle", "replace", "deactivate");
    private static final String CURRENT_MONTH_KEY = "growth.phase.current_month";
    private static final String CURRENT_PHASE_KEY = "growth.phase.current";
    private static final String E1_PHASE_SCOPE = "E1";
    private static final String E5_MAX_DEVICES_PER_USER_KEY = "device.e5.maxDevicesPerUser";
    private static final int SKU_IMAGE_ASSET_ID_MAX_LENGTH = 512;
    private static final int SKU_IMAGE_OBJECT_KEY_MAX_LENGTH = 255;
    private static final int SKU_IMAGE_PREVIEW_URL_MAX_LENGTH = 4096;
    private static final Pattern E1_GENERATION_ID = Pattern.compile("^[a-z0-9-]{1,80}$");
    private static final Set<String> E1_GATE_FIELDS = Set.of(
            "releaseMonth",
            "phase",
            "discount",
            "tradeinDiscount",
            "eligibility",
            "phaseOffset",
            "forceUnlock");
    private static final Set<String> E1_GATE_STATUSES = Set.of("active", "archived");
    private static final Set<String> E1_PHASE_STATUSES = Set.of("active", "archived");
    private static final Set<String> E3_CONFIG_KEYS = Set.of(
            "degradeEarly",
            "degradeMid",
            "degradeLate",
            "stageEarlyEnd",
            "stageMidEnd",
            "cycleMonths",
            "minEfficiency",
            "taskLockS1",
            "taskLockPro",
            "taskLockRack",
            "salvagePct",
            "eligibility",
            "minHoldingMonths",
            "promoMult",
            "promoCooldownDays",
            "promoMaxPerSession",
            "promoDelaySeconds",
            "promoMinAgeDays",
            "promoRoutes",
            "inventorySoftMax");
    private final DeviceOpsRepository deviceRepository;
    private final DeviceCatalogRepository catalogRepository;
    private final PlatformConfigFacade configFacade;
    private final TreasuryLedgerPostingFacade ledgerPostingFacade;
    private final AuditLogService auditLogService;
    private final Clock clock;
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;

    public ApiResult<Map<String, Object>> overview() {
        Map<String, Object> response = new LinkedHashMap<>(deviceRepository.overviewCounters());
        response.put("domain", "E");
        response.put("service", "nexion-backend");
        response.put("generatedAt", LocalDateTime.now(clock));
        response.put("maxDevicesPerUser", readOptionalPositiveInt(E5_MAX_DEVICES_PER_USER_KEY));
        response.put("sources", List.of(
                "nx_user_device",
                "nx_user_device_runtime",
                "nx_compute_datacenter",
                "nx_compute_dc_ops_state",
                "nx_config_item:" + E5_MAX_DEVICES_PER_USER_KEY));
        return ApiResult.ok(response);
    }

    public ApiResult<PageResult<DeviceOpsView>> devices(DeviceOpsQueryRequest request) {
        return ApiResult.ok(deviceRepository.pageDevices(request));
    }

    public ApiResult<List<DeviceDatacenterView>> datacenters() {
        return ApiResult.ok(deviceRepository.datacenterSummaries());
    }

    public ApiResult<List<DeviceOpsView>> userDevices(Long userId, int limit) {
        if (userId == null || userId <= 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "USER_ID_REQUIRED");
        }
        return ApiResult.ok(deviceRepository.listUserDevices(userId, Math.max(1, Math.min(200, limit))));
    }

    @Transactional
    public ApiResult<PageResult<DeviceSkuView>> skus(DeviceSkuQueryRequest request) {
        PageResult<DeviceSkuView> page = catalogRepository.pageSkus(request);
        return ApiResult.ok(page);
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
        DeviceSkuUpsertRequest writeRequest = normalizeSkuPhaseRequest(request);
        DeviceSkuView created = catalogRepository.createSku(skuId, writeRequest, LocalDateTime.now(clock));
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
        DeviceSkuUpsertRequest writeRequest = normalizeSkuPhaseRequest(request);
        DeviceSkuView updated = catalogRepository.updateSku(normalized, writeRequest, LocalDateTime.now(clock)).orElse(before);
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

    @Transactional
    public ApiResult<PageResult<DeviceReviewView>> reviews(DeviceReviewQueryRequest request) {
        PageResult<DeviceReviewView> page = catalogRepository.pageReviews(request);
        return ApiResult.ok(page);
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

    @Transactional
    public ApiResult<PageResult<DeviceTaskView>> tasks(DeviceTaskQueryRequest request) {
        PageResult<DeviceTaskView> page = catalogRepository.pageTasks(request);
        return ApiResult.ok(page);
    }

    /** E6 算力与设备配置只读聚合:从 nx_config_item 读取覆盖值,缺失时回落到注册表默认。 */
    public ApiResult<ComputeConfigView> computeConfig() {
        var flags = ComputeConfigRegistry.FLAGS.stream()
                .map(f -> new ComputeConfigView.FlagView(f.key(), f.label(), f.desc(), readFlag(f), f.frontendEffect())).toList();
        var coeffs = ComputeConfigRegistry.COEFFICIENTS.stream()
                .map(c -> new ComputeConfigView.CoeffView(c.key(), c.label(),
                        readVal(ComputeConfigRegistry.coeffKey(c.key()), c.defaultVal()), c.unit(), c.desc(), c.frontendEffect())).toList();
        var yields = ComputeConfigRegistry.YIELD_ESTIMATE.stream()
                .map(y -> new ComputeConfigView.YieldView(y.key(), y.label(),
                        readVal(ComputeConfigRegistry.yieldKey(y.key()), y.defaultVal()), y.unit())).toList();
        var tiers = ComputeConfigRegistry.GPU_TIERS.stream()
                .map(t -> new ComputeConfigView.GpuTierView(t.id(),
                        readVal(ComputeConfigRegistry.gpuTierKey(t.id(), "label"), t.label()),
                        t.desc(), t.defaultModel(),
                        readVal(ComputeConfigRegistry.gpuTierKey(t.id(), "tops"), t.defaultTops()),
                        readKeywords(t))).toList();
        var dl = new ComputeConfigView.DownloadView(
                readVal(ComputeConfigRegistry.downloadKey("url"), ""),
                readVal(ComputeConfigRegistry.downloadKey("zhTitle"), "电脑显卡算力共享"),
                readVal(ComputeConfigRegistry.downloadKey("zhGuide"), "下载桌面客户端,使用同一账号登录,连接后电脑会出现在设备仓库中。"),
                readVal(ComputeConfigRegistry.downloadKey("enTitle"), "Computer GPU share"),
                readVal(ComputeConfigRegistry.downloadKey("enGuide"), "Download the desktop client, sign in with the same account, and the computer appears in device inventory after connection."));
        return ApiResult.ok(new ComputeConfigView("E6", flags, coeffs, yields, tiers, dl, List.of("nx_config_item:E.compute.*")));
    }

    /** E6 单参数 PATCH:校验 paramKey 白名单 + 值规则,写入 nx_config_item 并记审计。 */
    @Transactional
    public ApiResult<ComputeConfigParamResponse> updateComputeConfigParam(
            String paramKey, String idempotencyKey, ComputeConfigParamUpdateRequest request) {
        ApiResult<Map<String, Object>> command = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (command != null) {
            return ApiResult.fail(command.getCode(), command.getMessage());
        }
        if (!ComputeConfigRegistry.isComputeParamKey(paramKey)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COMPUTE_PARAM_KEY_INVALID");
        }
        String value = normalizeText(request.value());
        String error = validateComputeValue(paramKey, value);
        if (error != null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), error);
        }
        String before = configFacade.activeValue(paramKey).orElse("");
        configFacade.upsertAdminValue(paramKey, value, "STRING", ComputeConfigRegistry.CONFIG_GROUP, "E6 compute config");
        audit("E6_COMPUTE_CONFIG_CHANGED", "E6_COMPUTE_CONFIG", paramKey, request.operator(), detail(
                "paramKey", paramKey, "before", before, "after", value,
                "reason", request.reason().trim(), "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(new ComputeConfigParamResponse(paramKey, value, LocalDateTime.now(clock).toString()));
    }

    private String validateComputeValue(String paramKey, String value) {
        if (paramKey.equals(ComputeConfigRegistry.flagKey("computeShareEnabled"))) {
            return Set.of("on", "off").contains(value) ? null : "COMPUTE_FLAG_INVALID";
        }
        if (paramKey.equals(ComputeConfigRegistry.coeffKey("h5BaseFactor"))) {
            BigDecimal v = parseComputeNumber(value);
            return (v != null && v.signum() >= 0 && v.compareTo(BigDecimal.ONE) <= 0) ? null : "COMPUTE_COEFF_INVALID";
        }
        if (paramKey.equals(ComputeConfigRegistry.coeffKey("continuityFullHours"))) {
            BigDecimal v = parseComputeNumber(value);
            return (v != null && v.signum() > 0) ? null : "COMPUTE_COEFF_INVALID";
        }
        if (paramKey.startsWith(ComputeConfigRegistry.PARAM_PREFIX + "yieldEstimate.")) {
            BigDecimal v = parseComputeNumber(value);
            return (v != null && v.signum() > 0) ? null : "COMPUTE_YIELD_INVALID";
        }
        if (paramKey.startsWith(ComputeConfigRegistry.PARAM_PREFIX + "gpuTier.")) {
            String tail = paramKey.substring((ComputeConfigRegistry.PARAM_PREFIX + "gpuTier.").length());
            if (tail.endsWith(".tops")) {
                BigDecimal v = parseComputeNumber(value);
                return (v != null && v.signum() > 0) ? null : "COMPUTE_TOPS_INVALID";
            }
            if (tail.endsWith(".label")) {
                return (value.isBlank() || value.length() > 24) ? "COMPUTE_LABEL_INVALID" : null;
            }
            if (tail.contains("keyword")) {
                return value.length() > 48 ? "COMPUTE_KEYWORD_INVALID" : null;
            }
        }
        if (paramKey.equals(ComputeConfigRegistry.downloadKey("url"))) {
            if (value.isBlank()) {
                return null;
            }
            return (value.length() <= 300 && value.startsWith("https://")) ? null : "COMPUTE_URL_INVALID";
        }
        if (paramKey.startsWith(ComputeConfigRegistry.PARAM_PREFIX + "download.")) {
            return value.length() > 320 ? "COMPUTE_DOWNLOAD_TEXT_INVALID" : null;
        }
        return null;
    }

    private BigDecimal parseComputeNumber(String v) {
        try {
            return new BigDecimal(v.trim());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    @Transactional
    public ApiResult<List<DevicePhoneTierRewardView>> phoneTierRewards() {
        List<DevicePhoneTierRewardView> rewards = catalogRepository.listPhoneTierRewards();
        return ApiResult.ok(rewards);
    }

    @Transactional
    public ApiResult<DevicePhoneTierRewardView> updatePhoneTierReward(
            Integer tier,
            String idempotencyKey,
            DevicePhoneTierRewardUpdateRequest request) {
        ApiResult<Map<String, Object>> command = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (command != null) {
            return ApiResult.fail(command.getCode(), command.getMessage());
        }
        if (tier == null || tier < 1 || tier > 5) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "PHONE_TIER_INVALID");
        }
        if ((request.dailyUsdt() == null || request.dailyUsdt().compareTo(BigDecimal.ZERO) <= 0)
                && (request.dailyNex() == null || request.dailyNex().compareTo(BigDecimal.ZERO) <= 0)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "PHONE_TIER_REWARD_REQUIRED");
        }
        if (request.dailyUsdt() != null && request.dailyUsdt().compareTo(BigDecimal.ZERO) <= 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "PHONE_TIER_USDT_INVALID");
        }
        if (request.dailyNex() != null && request.dailyNex().compareTo(BigDecimal.ZERO) <= 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "PHONE_TIER_NEX_INVALID");
        }
        DevicePhoneTierRewardView before = catalogRepository.findPhoneTierReward(tier).orElse(null);
        if (before == null) {
            return ApiResult.fail(404, "PHONE_TIER_NOT_FOUND");
        }
        DevicePhoneTierRewardView updated = catalogRepository
                .updatePhoneTierReward(tier, request, LocalDateTime.now(clock))
                .orElse(before);
        audit("E2_PHONE_TIER_REWARD_CHANGED", "PHONE_TIER_REWARD", String.valueOf(tier), request.operator(), detail(
                "tier", tier,
                "beforeDailyUsdt", before.dailyUsdt(),
                "afterDailyUsdt", updated.dailyUsdt(),
                "beforeDailyNex", before.dailyNex(),
                "afterDailyNex", updated.dailyNex(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
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
                "taskClass", created.taskClass(),
                "model", created.model(),
                "minReward", created.minReward(),
                "maxReward", created.maxReward(),
                "minVram", created.minVram(),
                "killInit", created.killInit(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(created);
    }

    public ApiResult<DeviceTaskView> updateTask(String taskId, String idempotencyKey, DeviceTaskUpsertRequest request) {
        ApiResult<DeviceTaskView> guard = requireTaskCommand(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        String normalized = normalizeId(taskId);
        DeviceTaskView before = catalogRepository.findTask(normalized).orElse(null);
        if (before == null) {
            return ApiResult.fail(404, "TASK_NOT_FOUND");
        }
        DeviceTaskView updated = catalogRepository.updateTask(normalized, request, LocalDateTime.now(clock)).orElse(before);
        audit("E2_TASK_UPDATED", "DEVICE_TASK", normalized, request.operator(), detail(
                "taskId", normalized,
                "beforeName", before.name(),
                "afterName", updated.name(),
                "beforePrice", before.price(),
                "afterPrice", updated.price(),
                "beforeRequirement", before.requirement(),
                "afterRequirement", updated.requirement(),
                "beforeTaskClass", before.taskClass(),
                "afterTaskClass", updated.taskClass(),
                "beforeModel", before.model(),
                "afterModel", updated.model(),
                "beforeMinReward", before.minReward(),
                "afterMinReward", updated.minReward(),
                "beforeMaxReward", before.maxReward(),
                "afterMaxReward", updated.maxReward(),
                "beforeMinVram", before.minVram(),
                "afterMinVram", updated.minVram(),
                "beforeKillInit", before.killInit(),
                "afterKillInit", updated.killInit(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
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
        ApiResult<DeviceTaskView> inUseGuard = requireTaskNotReferencedBySku(normalized, status);
        if (inUseGuard != null) {
            return inUseGuard;
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
        ApiResult<Map<String, Object>> inUseGuard = requireTaskNotReferencedBySku(normalized);
        if (inUseGuard != null) {
            return inUseGuard;
        }
        catalogRepository.softDeleteTask(normalized, LocalDateTime.now(clock));
        audit("E2_TASK_DELETED", "DEVICE_TASK", normalized, request.operator(), detail(
                "taskId", normalized,
                "name", before.name(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(detail("taskId", normalized, "deleted", true));
    }

    private ApiResult<DeviceTaskView> requireTaskNotReferencedBySku(String taskId, String nextStatus) {
        if (!"inactive".equals(nextStatus)) {
            return null;
        }
        List<DeviceSkuView> refs = catalogRepository.findSkusByAiUnlocks(taskId);
        if (refs.isEmpty()) {
            return null;
        }
        return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), taskInUseBySkuMessage(refs));
    }

    private ApiResult<Map<String, Object>> requireTaskNotReferencedBySku(String taskId) {
        List<DeviceSkuView> refs = catalogRepository.findSkusByAiUnlocks(taskId);
        if (refs.isEmpty()) {
            return null;
        }
        return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), taskInUseBySkuMessage(refs));
    }

    private String taskInUseBySkuMessage(List<DeviceSkuView> refs) {
        List<String> labels = refs.stream()
                .map(sku -> String.format("%s(%s)", sku.name(), sku.skuId()))
                .toList();
        return "任务正在被 E1 SKU 使用,请先到 E1 修改这些 SKU 的解锁算力池:" + String.join("、", labels);
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

    public ApiResult<DeviceOrderView> updateOrderState(String orderNo, String idempotencyKey, DeviceOrderStateRequest request) {
        ApiResult<Map<String, Object>> command = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (command != null) {
            return ApiResult.fail(command.getCode(), command.getMessage());
        }
        String toState = normalizeOrderState(request.state());
        if (!ORDER_MAIN_FLOW.contains(toState)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "ORDER_STATE_INVALID");
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
        if (!canMoveOrderMainState(fromState, toState)) {
            return ApiResult.fail(
                    OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(),
                    OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        DeviceOrderView updated = catalogRepository.updateOrderState(normalizedOrderNo, toState, LocalDateTime.now(clock)).orElse(before);
        audit("E4_ORDER_STATE_CHANGED", "DEVICE_ORDER", normalizedOrderNo, request.operator(), detail(
                "orderNo", normalizedOrderNo,
                "fromState", fromState,
                "toState", toState,
                "amount", before.amount(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    public ApiResult<Map<String, Object>> e1GenerationGates() {
        int platformMonth = currentPlatformMonth();
        List<DeviceGenerationGateView> gates = catalogRepository.listGenerationGates(false);
        Map<String, String> configValues = e1GateConfigValues(gates);
        List<DevicePhaseView> phases = e1PhaseDefs();
        GrowthRhythmSnapshot rhythm = GrowthRhythmSnapshot.from(configFacade, readTimeSeedPolicy);
        List<String> phaseOrder = phases.stream().map(DevicePhaseView::p).toList();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "E1");
        response.put("phaseOrder", phaseOrder);
        response.put("phases", phases);
        response.put("platformMonth", platformMonth);
        response.put("phaseCurrent", currentE1PhaseId(platformMonth, phases));
        response.put("h1Rhythm", rhythm.summary());
        response.put("h1DeviceReleasePacingPct", rhythm.deviceReleasePacingPct());
        response.put("releases", e1GenerationReleases(gates));
        response.put("configValues", configValues);
        response.put("allowedFields", List.of("releaseMonth", "phase", "discount", "eligibility", "phaseOffset", "forceUnlock"));
        response.put("sources", List.of(
                "nx_admin_phase_config",
                "nx_admin_device_generation_gate",
                "nx_config_item:" + CURRENT_PHASE_KEY,
                "nx_config_item:" + CURRENT_MONTH_KEY,
                "H1 growth rhythm facade",
                "E5 eligibility"));
        return ApiResult.ok(response);
    }

    @Transactional
    public ApiResult<Map<String, Object>> createE1Phase(String idempotencyKey, DevicePhaseUpsertRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (request == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "E1_PHASE_REQUEST_REQUIRED");
        }
        String label = normalizePhaseLabel(request.label());
        if (catalogRepository.findPhaseByLabel(E1_PHASE_SCOPE, label).isPresent()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "E1_PHASE_NAME_EXISTS");
        }
        DevicePhaseView created = catalogRepository.savePhase(
                E1_PHASE_SCOPE,
                "",
                label,
                normalizeText(request.meta()),
                normalizeText(request.skus()),
                request.sortOrder() == null ? nextPhaseSortOrder() : normalizePhaseSortOrder(request.sortOrder()),
                normalizePhaseStatus(request.status()),
                LocalDateTime.now(clock));
        audit("E1_PHASE_CREATED", "DEVICE_PHASE", created.p(), request.operator(), detail(
                "id", created.p(),
                "label", created.label(),
                "meta", created.meta(),
                "skus", created.skus(),
                "sortOrder", created.sortOrder(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return e1GenerationGates();
    }

    @Transactional
    public ApiResult<Map<String, Object>> patchE1Phase(String phaseId, String idempotencyKey, DevicePhaseUpsertRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (request == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "E1_PHASE_REQUEST_REQUIRED");
        }
        String currentPhaseId = matchConfiguredE1PhaseId(phaseId, true);
        DevicePhaseView before = catalogRepository.findPhase(E1_PHASE_SCOPE, currentPhaseId).orElse(null);
        if (before == null) {
            return ApiResult.fail(404, "E1_PHASE_NOT_FOUND");
        }
        String nextLabel = StringUtils.hasText(request.label()) ? normalizePhaseLabel(request.label()) : before.label();
        if (catalogRepository.findPhaseByLabel(E1_PHASE_SCOPE, nextLabel)
                .filter(phase -> !currentPhaseId.equals(phase.p()))
                .isPresent()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "E1_PHASE_NAME_EXISTS");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        DevicePhaseView updated = catalogRepository.savePhase(
                E1_PHASE_SCOPE,
                currentPhaseId,
                nextLabel,
                request.meta() == null ? before.meta() : normalizeText(request.meta()),
                request.skus() == null ? before.skus() : normalizeText(request.skus()),
                request.sortOrder() == null ? before.sortOrder() : normalizePhaseSortOrder(request.sortOrder()),
                request.status() == null ? before.status() : normalizePhaseStatus(request.status()),
                now);
        audit("E1_PHASE_UPDATED", "DEVICE_PHASE", updated.p(), request.operator(), detail(
                "before", phaseSnapshot(before),
                "after", phaseSnapshot(updated),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return e1GenerationGates();
    }

    @Transactional
    public ApiResult<Map<String, Object>> archiveE1Phase(String phaseId, String idempotencyKey, DevicePhaseArchiveRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String normalized = matchConfiguredE1PhaseId(phaseId, true);
        DevicePhaseView before = catalogRepository.findPhase(E1_PHASE_SCOPE, normalized).orElse(null);
        if (before == null) {
            return ApiResult.fail(404, "E1_PHASE_NOT_FOUND");
        }
        if (catalogRepository.listPhases(E1_PHASE_SCOPE, false).size() <= 1) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "E1_PHASE_MIN_ONE_REQUIRED");
        }
        if (normalized.equals(currentE1PhaseId(currentPlatformMonth(), e1PhaseDefs()))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "E1_PHASE_IS_CURRENT");
        }
        int skuRefs = catalogRepository.countSkusByUnlockPhase(normalized);
        int gateRefs = catalogRepository.countGenerationGatesByPhase(normalized);
        if (skuRefs > 0 || gateRefs > 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "E1_PHASE_IN_USE");
        }
        catalogRepository.archivePhase(E1_PHASE_SCOPE, normalized, LocalDateTime.now(clock));
        audit("E1_PHASE_ARCHIVED", "DEVICE_PHASE", normalized, request.operator(), detail(
                "phase", phaseSnapshot(before),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return e1GenerationGates();
    }

    @Transactional
    public ApiResult<Map<String, Object>> setE1CurrentPhase(String phaseId, String idempotencyKey, DevicePhaseCurrentRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        catalogRepository.backfillPhaseReferences(E1_PHASE_SCOPE, LocalDateTime.now(clock));
        List<DevicePhaseView> phases = e1PhaseDefs();
        String normalized = matchE1PhaseId(phaseId, phases);
        DevicePhaseView target = phases.stream()
                .filter(phase -> phase.p().equals(normalized))
                .findFirst()
                .orElse(null);
        if (target == null) {
            return ApiResult.fail(404, "E1_PHASE_NOT_FOUND");
        }
        String before = currentE1PhaseId(currentPlatformMonth(), phases);
        configFacade.upsertAdminValue(
                CURRENT_PHASE_KEY,
                normalized,
                "STRING",
                "growth",
                "E1 current phase override");
        audit("E1_PHASE_CURRENT_CHANGED", "DEVICE_PHASE", normalized, request.operator(), detail(
                "before", before,
                "after", normalized,
                "phase", phaseSnapshot(target),
                "configKey", CURRENT_PHASE_KEY,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return e1GenerationGates();
    }

    @Transactional
    public ApiResult<Map<String, Object>> createE1GenerationGate(String idempotencyKey, DeviceGenerationGateUpsertRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (request == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "E1_GATE_REQUEST_REQUIRED");
        }
        catalogRepository.backfillPhaseReferences(E1_PHASE_SCOPE, LocalDateTime.now(clock));
        String skuId = normalizeGenerationId(request.skuId());
        if (!StringUtils.hasText(skuId)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "E1_GATE_SKU_ID_INVALID");
        }
        DeviceGenerationGateView existing = catalogRepository.findGenerationGate(skuId).orElse(null);
        if (existing != null && "active".equals(existing.status())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "E1_GATE_ALREADY_EXISTS");
        }
        DeviceSkuView sku = catalogRepository.findSku(skuId).orElse(null);
        if (sku == null) {
            return ApiResult.fail(404, "SKU_NOT_FOUND");
        }
        String name = StringUtils.hasText(request.name()) ? request.name().trim() : sku.name();
        DeviceGenerationGateView created = catalogRepository.saveGenerationGate(
                skuId,
                name,
                normalizeReleaseMonth(request.releaseMonth()),
                normalizeE1Phase(request.phase()),
                normalizeDiscount(request.discount()),
                request.eligibility(),
                normalizePhaseOffset(request.phaseOffset() == null ? 0 : request.phaseOffset()),
                request.forceUnlock(),
                normalizeGenerationGateStatus(request.status()),
                LocalDateTime.now(clock));
        audit("E1_GENERATION_GATE_CREATED", "DEVICE_GENERATION_GATE", skuId, request.operator(), detail(
                "generationId", skuId,
                "name", created.name(),
                "releaseMonth", created.releaseMonth(),
                "phase", created.phase(),
                "discount", created.discount(),
                "eligibility", created.eligibility(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return e1GenerationGates();
    }

    @Transactional
    public ApiResult<Map<String, Object>> patchE1GenerationGate(String skuId, String idempotencyKey, DeviceGenerationGatePatchRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String normalized = normalizeGenerationId(skuId);
        catalogRepository.backfillPhaseReferences(E1_PHASE_SCOPE, LocalDateTime.now(clock));
        DeviceGenerationGateView before = catalogRepository.findGenerationGate(normalized).orElse(null);
        if (before == null) {
            return ApiResult.fail(404, "E1_GATE_NOT_FOUND");
        }
        DeviceGenerationGateView updated = catalogRepository.saveGenerationGate(
                normalized,
                StringUtils.hasText(request.name()) ? request.name().trim() : before.name(),
                request.releaseMonth() == null ? before.releaseMonth() : normalizeReleaseMonth(request.releaseMonth()),
                request.phase() == null ? before.phase() : normalizeE1Phase(request.phase()),
                request.discount() == null ? before.discount() : normalizeDiscount(request.discount()),
                request.eligibility() == null ? before.eligibility() : request.eligibility(),
                request.phaseOffset() == null ? before.phaseOffset() : normalizePhaseOffset(request.phaseOffset()),
                request.forceUnlock() == null ? before.forceUnlock() : request.forceUnlock(),
                request.status() == null ? before.status() : normalizeGenerationGateStatus(request.status()),
                LocalDateTime.now(clock));
        audit("E1_GENERATION_GATE_UPDATED", "DEVICE_GENERATION_GATE", normalized, request.operator(), detail(
                "generationId", normalized,
                "before", generationGateSnapshot(before),
                "after", generationGateSnapshot(updated),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return e1GenerationGates();
    }

    @Transactional
    public ApiResult<Map<String, Object>> archiveE1GenerationGate(String skuId, String idempotencyKey, DeviceGenerationGateArchiveRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String normalized = normalizeGenerationId(skuId);
        DeviceGenerationGateView before = catalogRepository.findGenerationGate(normalized).orElse(null);
        if (before == null) {
            return ApiResult.fail(404, "E1_GATE_NOT_FOUND");
        }
        catalogRepository.archiveGenerationGate(normalized, LocalDateTime.now(clock));
        audit("E1_GENERATION_GATE_ARCHIVED", "DEVICE_GENERATION_GATE", normalized, request.operator(), detail(
                "generationId", normalized,
                "name", before.name(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return e1GenerationGates();
    }

    public ApiResult<Map<String, Object>> updateE1GenerationGate(String idempotencyKey, E3ConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        catalogRepository.backfillPhaseReferences(E1_PHASE_SCOPE, LocalDateTime.now(clock));
        String[] key = normalizeE1GateKey(request.key());
        String value = normalizeE1GateValue(key[1], request.value());
        DeviceGenerationGateView before = catalogRepository.findGenerationGate(key[0]).orElse(null);
        if (before == null) {
            return ApiResult.fail(404, "E1_GATE_NOT_FOUND");
        }
        String oldValue = generationGateFieldValue(before, key[1]);
        DeviceGenerationGateView updated = saveGenerationGateField(before, key[1], value);
        audit("E1_GENERATION_GATE_CHANGED", "DEVICE_GENERATION_GATE", key[0], request.operator(), detail(
                "generationId", key[0],
                "field", key[1],
                "oldValue", oldValue,
                "newValue", generationGateFieldValue(updated, key[1]),
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
        E3ConfigValue value = normalizeE3Value(key, request.value());
        Map<String, String> before = deviceRepository.e3Config();
        deviceRepository.upsertE3Config(key, value.value(), value.valueType(), operator(request.operator()));
        Map<String, String> after = deviceRepository.e3Config();
        audit("E3_CONFIG_CHANGED", "DEVICE_E3_CONFIG", key, request.operator(), detail(
                "key", key,
                "oldValue", before.get(key),
                "newValue", after.get(key),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return e3Overview();
    }

    public ApiResult<DeviceTradeinOverviewView> e3TradeinOverview() {
        Map<String, String> config = deviceRepository.e3Config();
        int cliffMonth = parsePositiveInt(config.get("stageMidEnd"), 0) + 1;
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

    public ApiResult<DeviceDatacenterView> createDatacenter(String idempotencyKey, DeviceDatacenterUpsertRequest request) {
        ApiResult<DeviceDatacenterView> guard = requireDatacenterCommand(idempotencyKey, request, true);
        if (guard != null) {
            return guard;
        }
        String dc = normalizeDc(request.dcLocation());
        if (deviceRepository.findDatacenter(dc).isPresent()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DATACENTER_ALREADY_EXISTS");
        }
        DeviceDatacenterUpsertRequest normalized = normalizeDatacenterRequest(dc, request);
        DeviceDatacenterView created = deviceRepository.createDatacenter(normalized, operator(request.operator()), LocalDateTime.now(clock));
        audit("E5_DATACENTER_CREATED", "DEVICE_DATACENTER", created.dcLocation(), request.operator(), detail(
                "dcLocation", created.dcLocation(),
                "regionLabel", created.regionLabel(),
                "status", created.status(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(created);
    }

    public ApiResult<DeviceDatacenterView> updateDatacenter(String dcLocation, String idempotencyKey, DeviceDatacenterUpsertRequest request) {
        ApiResult<DeviceDatacenterView> guard = requireDatacenterCommand(idempotencyKey, request, false);
        if (guard != null) {
            return guard;
        }
        String dc = normalizeDc(dcLocation);
        DeviceDatacenterView before = deviceRepository.findDatacenter(dc).orElse(null);
        if (before == null) {
            return ApiResult.fail(404, "DATACENTER_NOT_FOUND");
        }
        if (StringUtils.hasText(request.dcLocation()) && !dc.equals(request.dcLocation().trim())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DATACENTER_LOCATION_IMMUTABLE");
        }
        DeviceDatacenterUpsertRequest normalized = normalizeDatacenterRequest(dc, request);
        DeviceDatacenterView updated = deviceRepository.updateDatacenter(dc, normalized, operator(request.operator()), LocalDateTime.now(clock)).orElse(before);
        audit("E5_DATACENTER_UPDATED", "DEVICE_DATACENTER", dc, request.operator(), detail(
                "dcLocation", dc,
                "beforeRegionLabel", before.regionLabel(),
                "afterRegionLabel", updated.regionLabel(),
                "beforeStatus", before.status(),
                "afterStatus", updated.status(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    public ApiResult<Map<String, Object>> deleteDatacenter(String dcLocation, String idempotencyKey, DatacenterOpsRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String dc = normalizeDc(dcLocation);
        DeviceDatacenterView before = deviceRepository.findDatacenter(dc).orElse(null);
        if (before == null) {
            return ApiResult.fail(404, "DATACENTER_NOT_FOUND");
        }
        deviceRepository.softDeleteDatacenter(dc, operator(request.operator()), LocalDateTime.now(clock));
        audit("E5_DATACENTER_DELETED", "DEVICE_DATACENTER", dc, request.operator(), detail(
                "dcLocation", dc,
                "regionLabel", before.regionLabel(),
                "status", before.status(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(detail("dcLocation", dc, "deleted", true));
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

    private ApiResult<DeviceDatacenterView> requireDatacenterCommand(String idempotencyKey, DeviceDatacenterUpsertRequest request, boolean create) {
        ApiResult<Map<String, Object>> command = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (command != null) {
            return ApiResult.fail(command.getCode(), command.getMessage());
        }
        if (request == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DATACENTER_REQUEST_REQUIRED");
        }
        if (create && !StringUtils.hasText(request.dcLocation())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DATACENTER_LOCATION_REQUIRED");
        }
        if (!StringUtils.hasText(request.regionLabel())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DATACENTER_REGION_LABEL_REQUIRED");
        }
        if (normalizeDatacenterStatus(request.status()) == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DATACENTER_STATUS_INVALID");
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
        catalogRepository.backfillPhaseReferences(E1_PHASE_SCOPE, LocalDateTime.now(clock));
        if (!"Share".equals(tier) && !isConfiguredE1PhaseId(request.unlockPhase())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SKU_UNLOCK_PHASE_INVALID");
        }
        if (normalizeSkuStatus(request.status()) == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SKU_STATUS_INVALID");
        }
        ApiResult<DeviceSkuView> mediaGuard = requireSkuMediaFields(request);
        if (mediaGuard != null) {
            return mediaGuard;
        }
        return null;
    }

    private ApiResult<DeviceSkuView> requireSkuMediaFields(DeviceSkuUpsertRequest request) {
        if (!validMediaAssetId(request.imageAssetId())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SKU_IMAGE_ASSET_ID_INVALID");
        }
        if (!validMediaObjectKey(request.imageObjectKey())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SKU_IMAGE_OBJECT_KEY_INVALID");
        }
        if (!validMediaPreviewUrl(request.imagePreviewUrl())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SKU_IMAGE_PREVIEW_URL_INVALID");
        }
        return null;
    }

    private DeviceSkuUpsertRequest normalizeSkuPhaseRequest(DeviceSkuUpsertRequest request) {
        String tier = normalizeExact(request.tier());
        String unlockPhase = "Share".equals(tier) ? "" : normalizeE1Phase(request.unlockPhase());
        return new DeviceSkuUpsertRequest(
                request.skuId(),
                request.name(),
                request.tier(),
                request.tagline(),
                request.badge(),
                request.gpu(),
                request.vram(),
                request.hashRate(),
                request.power(),
                request.datacenter(),
                request.price(),
                request.dailyEarn(),
                request.dailyEarnNex(),
                request.shareYieldMin(),
                request.shareYieldMax(),
                request.baseRate(),
                request.sold(),
                request.stock(),
                request.rating(),
                request.reviews(),
                request.aiImageGenPerMin(),
                request.aiLlmTokensPerSec(),
                request.aiVideoMinPerHour(),
                request.aiFineTuneMins(),
                request.aiUnlocks(),
                request.features(),
                request.generation(),
                request.lifecycle(),
                request.supersededBy(),
                request.tradeinDiscount(),
                unlockPhase,
                request.purchaseGate(),
                request.imageAssetId(),
                request.imageObjectKey(),
                request.imagePreviewUrl(),
                request.tag(),
                request.status(),
                request.reason(),
                request.operator());
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
        if (!allows(request.taskClass(), TASK_CLASSES)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TASK_CLASS_INVALID");
        }
        if (!StringUtils.hasText(request.model())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TASK_MODEL_REQUIRED");
        }
        if (request.minReward() == null
                || request.maxReward() == null
                || request.minReward().compareTo(BigDecimal.ZERO) <= 0
                || request.maxReward().compareTo(request.minReward()) < 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TASK_REWARD_RANGE_INVALID");
        }
        if (!StringUtils.hasText(request.minVram())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TASK_MIN_VRAM_REQUIRED");
        }
        if (!allows(request.killInit(), TASK_KILL_INIT_STATES)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TASK_KILL_INIT_INVALID");
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
        if ("E4_ORDER_REFUNDED".equals(auditAction)) {
            postRefundLedger(normalizedOrderNo, before);
        }
        audit(auditAction, "DEVICE_ORDER", normalizedOrderNo, request.operator(), detail(
                "orderNo", normalizedOrderNo,
                "fromState", fromState,
                "toState", toState,
                "amount", before.amount(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    private void postRefundLedger(String orderNo, DeviceOrderView before) {
        BigDecimal amount = before.amount() == null ? BigDecimal.ZERO : before.amount();
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        ledgerPostingFacade.postLedgerEntry(
                "E4-REFUND-" + orderNo,
                parseUserId(before.userNo()),
                "REFUND",
                "USDT",
                "IN",
                amount,
                "SUCCESS",
                "E4 order refund reversal | orderNo=" + orderNo);
    }

    private Long parseUserId(String userNo) {
        if (!StringUtils.hasText(userNo)) {
            return 0L;
        }
        String digits = userNo.replaceAll("\\D+", "");
        if (!StringUtils.hasText(digits)) {
            return 0L;
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException ex) {
            return 0L;
        }
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

    private boolean canMoveOrderMainState(String fromState, String toState) {
        if (ORDER_FINAL_STATES.contains(fromState)) {
            return false;
        }
        if ("failed".equals(fromState) && "allocating".equals(toState)) {
            return true;
        }
        int fromIndex = ORDER_MAIN_FLOW.indexOf(fromState);
        int toIndex = ORDER_MAIN_FLOW.indexOf(toState);
        return fromIndex >= 0 && toIndex >= 0 && Math.abs(toIndex - fromIndex) == 1;
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

    private Map<String, String> e1GateConfigValues(List<DeviceGenerationGateView> gates) {
        Map<String, String> response = new LinkedHashMap<>();
        for (DeviceGenerationGateView gate : gates) {
            response.put("E.gen." + gate.id() + ".releaseMonth", String.valueOf(gate.releaseMonth()));
            response.put("E.gen." + gate.id() + ".phase", gate.phase());
            response.put("E.gen." + gate.id() + ".discount", decimalText(gate.discount()));
            response.put("E.gen." + gate.id() + ".eligibility", String.valueOf(Boolean.TRUE.equals(gate.eligibility())));
            response.put("E.gen." + gate.id() + ".phaseOffset", String.valueOf(gate.phaseOffset()));
            response.put("E.gen." + gate.id() + ".forceUnlock", String.valueOf(Boolean.TRUE.equals(gate.forceUnlock())));
        }
        return response;
    }

    private List<DevicePhaseView> e1PhaseDefs() {
        return catalogRepository.listPhases(E1_PHASE_SCOPE, false);
    }

    private List<Map<String, Object>> e1GenerationReleases(List<DeviceGenerationGateView> gates) {
        List<Map<String, Object>> response = new ArrayList<>();
        for (DeviceGenerationGateView gate : gates) {
            int releaseMonth = gate.releaseMonth() == null ? 0 : gate.releaseMonth();
            int phaseOffset = gate.phaseOffset() == null ? 0 : gate.phaseOffset();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", gate.id());
            row.put("name", gate.name());
            row.put("releaseMonth", releaseMonth);
            row.put("phase", gate.phase());
            row.put("discount", gate.discount());
            row.put("eligibility", Boolean.TRUE.equals(gate.eligibility()));
            row.put("phaseOffset", phaseOffset);
            row.put("forceUnlock", Boolean.TRUE.equals(gate.forceUnlock()));
            row.put("status", gate.status());
            row.put("effectiveReleaseMonth", releaseMonth + phaseOffset);
            response.add(row);
        }
        return response;
    }

    private int currentPlatformMonth() {
        int month = GrowthRhythmSnapshot.from(configFacade, readTimeSeedPolicy).currentMonth();
        return month >= 1 ? Math.min(month, 12) : 0;
    }

    private String phaseForMonth(int month, List<DevicePhaseView> phases) {
        if (month < 1 || phases.isEmpty()) {
            return "";
        }
        int index = Math.min(phases.size() - 1, Math.max(0, ((month - 1) * phases.size()) / 12));
        return phases.get(index).p();
    }

    private String currentE1PhaseId(int platformMonth, List<DevicePhaseView> phases) {
        return configFacade.activeValue(CURRENT_PHASE_KEY)
                .map(value -> matchE1PhaseId(value, phases))
                .filter(StringUtils::hasText)
                .orElse("");
    }

    private boolean readFlag(ComputeConfigRegistry.FlagDef f) {
        return configFacade.activeValue(ComputeConfigRegistry.flagKey(f.key())).map(s -> "on".equals(s.trim())).orElse(f.defaultOn());
    }

    private String readVal(String key, String fallback) {
        return configFacade.activeValue(key).filter(s -> !s.isBlank()).orElse(fallback);
    }

    private List<String> readKeywords(ComputeConfigRegistry.GpuTierDef t) {
        List<String> out = new ArrayList<>();
        for (String slot : ComputeConfigRegistry.KEYWORD_SLOTS) {
            String v = readVal(ComputeConfigRegistry.gpuTierKey(t.id(), slot), "");
            if (!v.isBlank()) {
                out.add(v);
            }
        }
        return out;
    }

    private int readInt(String configKey, int fallback) {
        return parseInt(configFacade.activeValue(configKey).orElse(null), fallback);
    }

    private Integer readOptionalPositiveInt(String configKey) {
        return configFacade.activeValue(configKey)
                .map(value -> parseInt(value, 0))
                .filter(value -> value > 0)
                .orElse(null);
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

    private List<String> csv(String value) {
        List<String> items = new ArrayList<>();
        if (!StringUtils.hasText(value)) {
            return items;
        }
        for (String item : value.split(",")) {
            String normalized = item.trim();
            if (StringUtils.hasText(normalized)) {
                items.add(normalized);
            }
        }
        return items;
    }

    private String text(Map<String, String> values, String key) {
        String value = values.get(key);
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    private String normalizeGenerationId(String raw) {
        String normalized = normalizeId(raw).toLowerCase(Locale.ROOT);
        return E1_GENERATION_ID.matcher(normalized).matches() ? normalized : "";
    }

    private int normalizeReleaseMonth(Integer month) {
        if (month == null || month < 1 || month > 12) {
            throw new IllegalArgumentException("E1_GATE_RELEASE_MONTH_INVALID");
        }
        return month;
    }

    private String normalizeE1Phase(String phase) {
        String normalized = matchConfiguredE1PhaseId(phase, false);
        if (!StringUtils.hasText(normalized)) {
            throw new IllegalArgumentException("E1_GATE_PHASE_INVALID");
        }
        return normalized;
    }

    private String matchConfiguredE1PhaseId(String phase, boolean allowArchived) {
        return matchE1PhaseId(phase, catalogRepository.listPhases(E1_PHASE_SCOPE, allowArchived));
    }

    private String matchE1PhaseId(String phase, List<DevicePhaseView> phases) {
        String requested = normalizeText(phase);
        if (!StringUtils.hasText(requested)) {
            return "";
        }
        return phases.stream()
                .filter(candidate -> requested.equals(candidate.p()) || requested.equals(candidate.label()))
                .map(DevicePhaseView::p)
                .findFirst()
                .orElse("");
    }

    private boolean isConfiguredE1PhaseId(String phase) {
        try {
            return StringUtils.hasText(matchConfiguredE1PhaseId(phase, false));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private String normalizePhaseLabel(String label) {
        String normalized = normalizeText(label);
        if (!StringUtils.hasText(normalized) || normalized.length() > 128) {
            throw new IllegalArgumentException("E1_PHASE_LABEL_INVALID");
        }
        return normalized;
    }

    private String normalizeText(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    private int normalizePhaseSortOrder(Integer sortOrder) {
        if (sortOrder == null || sortOrder < 0 || sortOrder > 9999) {
            throw new IllegalArgumentException("E1_PHASE_SORT_INVALID");
        }
        return sortOrder;
    }

    private String normalizePhaseStatus(String status) {
        String normalized = StringUtils.hasText(status) ? status.trim().toLowerCase(Locale.ROOT) : "active";
        if (!E1_PHASE_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("E1_PHASE_STATUS_INVALID");
        }
        return normalized;
    }

    private int nextPhaseSortOrder() {
        return catalogRepository.listPhases(E1_PHASE_SCOPE, true).stream()
                .map(DevicePhaseView::sortOrder)
                .filter(java.util.Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0) + 10;
    }

    private BigDecimal normalizeDiscount(BigDecimal discount) {
        if (discount == null || discount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("E1_GATE_DISCOUNT_INVALID");
        }
        return discount.setScale(2, RoundingMode.HALF_UP);
    }

    private int normalizePhaseOffset(Integer offset) {
        if (offset == null || offset < -12 || offset > 12) {
            throw new IllegalArgumentException("E1_GATE_PHASE_OFFSET_OUT_OF_RANGE");
        }
        return offset;
    }

    private String normalizeGenerationGateStatus(String status) {
        String normalized = StringUtils.hasText(status) ? status.trim().toLowerCase(Locale.ROOT) : "active";
        if (!E1_GATE_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("E1_GATE_STATUS_INVALID");
        }
        return normalized;
    }

    private String decimalText(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private Map<String, Object> generationGateSnapshot(DeviceGenerationGateView gate) {
        return detail(
                "id", gate.id(),
                "name", gate.name(),
                "releaseMonth", gate.releaseMonth(),
                "phase", gate.phase(),
                "discount", gate.discount(),
                "eligibility", gate.eligibility(),
                "phaseOffset", gate.phaseOffset(),
                "forceUnlock", gate.forceUnlock(),
                "status", gate.status());
    }

    private Map<String, Object> phaseSnapshot(DevicePhaseView phase) {
        return detail(
                "id", phase.p(),
                "label", phase.label(),
                "meta", phase.meta(),
                "skus", phase.skus(),
                "sortOrder", phase.sortOrder(),
                "status", phase.status());
    }

    private String generationGateFieldValue(DeviceGenerationGateView gate, String field) {
        return switch (field) {
            case "releaseMonth" -> String.valueOf(gate.releaseMonth());
            case "phase" -> gate.phase();
            case "discount", "tradeinDiscount" -> decimalText(gate.discount());
            case "eligibility" -> String.valueOf(Boolean.TRUE.equals(gate.eligibility()));
            case "phaseOffset" -> String.valueOf(gate.phaseOffset());
            case "forceUnlock" -> String.valueOf(Boolean.TRUE.equals(gate.forceUnlock()));
            default -> "";
        };
    }

    private DeviceGenerationGateView saveGenerationGateField(DeviceGenerationGateView before, String field, String value) {
        Integer releaseMonth = before.releaseMonth();
        String phase = before.phase();
        BigDecimal discount = before.discount();
        Boolean eligibility = before.eligibility();
        Integer phaseOffset = before.phaseOffset();
        Boolean forceUnlock = before.forceUnlock();
        if ("releaseMonth".equals(field)) {
            releaseMonth = normalizeReleaseMonth(Integer.parseInt(value));
        } else if ("phase".equals(field)) {
            phase = normalizeE1Phase(value);
        } else if ("discount".equals(field) || "tradeinDiscount".equals(field)) {
            discount = normalizeDiscount(new BigDecimal(value));
        } else if ("eligibility".equals(field)) {
            eligibility = Boolean.parseBoolean(value);
        } else if ("phaseOffset".equals(field)) {
            phaseOffset = normalizePhaseOffset(Integer.parseInt(value));
        } else if ("forceUnlock".equals(field)) {
            forceUnlock = Boolean.parseBoolean(value);
        }
        return catalogRepository.saveGenerationGate(
                before.id(),
                before.name(),
                releaseMonth,
                phase,
                discount,
                eligibility,
                phaseOffset,
                forceUnlock,
                before.status(),
                LocalDateTime.now(clock));
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
        if (!E1_GENERATION_ID.matcher(generationId).matches() || !E1_GATE_FIELDS.contains(field)) {
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
        if ("releaseMonth".equals(field)) {
            int month = parsePositiveOrNegativeInt(rawValue);
            return String.valueOf(normalizeReleaseMonth(month));
        }
        if ("phase".equals(field)) {
            return normalizeE1Phase(rawValue);
        }
        if ("discount".equals(field) || "tradeinDiscount".equals(field)) {
            return decimalText(normalizeDiscount(parseDecimal(rawValue)));
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
        normalized = switch (normalized) {
            case "taskLock.s1" -> "taskLockS1";
            case "taskLock.pro" -> "taskLockPro";
            case "taskLock.rack" -> "taskLockRack";
            case "promo.cooldownDays", "promoCooldownDays" -> "promoCooldownDays";
            case "promo.maxPerSession", "promoMaxPerSession" -> "promoMaxPerSession";
            case "promo.delaySec", "promoDelaySec", "promo.delaySeconds", "promoDelaySeconds" -> "promoDelaySeconds";
            case "promo.minAgeDays", "promoMinAgeDays" -> "promoMinAgeDays";
            case "promo.routes", "promoRoutes" -> "promoRoutes";
            default -> normalized;
        };
        if (!E3_CONFIG_KEYS.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported E3 config key");
        }
        return normalized;
    }

    private E3ConfigValue normalizeE3Value(String key, String raw) {
        if ("eligibility".equals(key)) {
            String value = normalizeText(raw);
            if (!Set.of("全部用户", "L2+ 持有者", "L3+ 持有者", "L4+ 持有者", "L5+ 持有者", "L6+ 持有者").contains(value)) {
                throw new IllegalArgumentException("eligibility config is invalid");
            }
            return new E3ConfigValue(value, "STRING");
        }
        if ("promoRoutes".equals(key)) {
            String value = normalizeText(raw);
            if (!Set.of("/me/devices", "/me", "/store", "/earn", "全部页面").contains(value)) {
                throw new IllegalArgumentException("promo routes config is invalid");
            }
            return new E3ConfigValue(value, "STRING");
        }
        BigDecimal value = parseDecimal(raw);
        if (key.startsWith("degrade")) {
            if (value.compareTo(BigDecimal.valueOf(-100)) < 0 || value.compareTo(BigDecimal.ZERO) > 0) {
                throw new IllegalArgumentException("degrade config must be -100-0");
            }
            return numberConfig(value.setScale(2, RoundingMode.HALF_UP));
        }
        if ("minEfficiency".equals(key) || "salvagePct".equals(key)) {
            if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new IllegalArgumentException("percent config must be 0-100");
            }
            return numberConfig(value.setScale(2, RoundingMode.HALF_UP));
        }
        if (key.endsWith("Days") || key.endsWith("Seconds") || key.endsWith("Max") || key.endsWith("Months")
                || "stageEarlyEnd".equals(key) || "stageMidEnd".equals(key) || "cycleMonths".equals(key)
                || "inventorySoftMax".equals(key) || "promoMaxPerSession".equals(key) || key.startsWith("taskLock")) {
            if (value.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("numeric config must be positive");
            }
            return numberConfig(BigDecimal.valueOf(value.setScale(0, RoundingMode.DOWN).longValue()));
        }
        return numberConfig(value.setScale(4, RoundingMode.HALF_UP));
    }

    private E3ConfigValue numberConfig(BigDecimal value) {
        return new E3ConfigValue(value.stripTrailingZeros().toPlainString(), "NUMBER");
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

    private DeviceDatacenterUpsertRequest normalizeDatacenterRequest(String dcLocation, DeviceDatacenterUpsertRequest request) {
        return new DeviceDatacenterUpsertRequest(
                dcLocation,
                request.regionLabel().trim(),
                normalizeDatacenterStatus(request.status()),
                normalizeDatacenterSortOrder(request.sortOrder()),
                request.reason(),
                request.operator());
    }

    private String normalizeDatacenterStatus(String status) {
        String normalized = StringUtils.hasText(status) ? status.trim().toLowerCase(Locale.ROOT) : "active";
        return DATACENTER_STATUSES.contains(normalized) ? normalized : null;
    }

    private int normalizeDatacenterSortOrder(Integer sortOrder) {
        if (sortOrder == null) {
            return 100;
        }
        return Math.max(0, Math.min(9999, sortOrder));
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

    private boolean validMediaAssetId(String assetId) {
        if (!StringUtils.hasText(assetId)) {
            return true;
        }
        String trimmed = assetId.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return trimmed.length() <= SKU_IMAGE_ASSET_ID_MAX_LENGTH
                && !lower.startsWith("http://")
                && !lower.startsWith("https://")
                && trimmed.indexOf('/') < 0
                && trimmed.indexOf('\\') < 0
                && trimmed.indexOf('\n') < 0
                && trimmed.indexOf('\r') < 0
                && trimmed.indexOf('\t') < 0;
    }

    private boolean validMediaObjectKey(String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            return true;
        }
        String trimmed = objectKey.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return trimmed.length() <= SKU_IMAGE_OBJECT_KEY_MAX_LENGTH
                && !trimmed.startsWith("/")
                && !trimmed.endsWith("/")
                && !lower.startsWith("http://")
                && !lower.startsWith("https://")
                && !lower.contains("..")
                && trimmed.indexOf('\\') < 0
                && trimmed.indexOf('\n') < 0
                && trimmed.indexOf('\r') < 0
                && trimmed.indexOf('\t') < 0;
    }

    private boolean validMediaPreviewUrl(String previewUrl) {
        if (!StringUtils.hasText(previewUrl)) {
            return true;
        }
        String trimmed = previewUrl.trim();
        return trimmed.length() <= SKU_IMAGE_PREVIEW_URL_MAX_LENGTH
                && trimmed.indexOf('\n') < 0
                && trimmed.indexOf('\r') < 0
                && trimmed.indexOf('\t') < 0;
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

    private record E3ConfigValue(String value, String valueType) {
    }

}
