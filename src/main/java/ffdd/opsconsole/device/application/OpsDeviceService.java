package ffdd.opsconsole.device.application;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.capacity.E3CapacityCurve;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.device.domain.ComputeConfigRegistry;
import ffdd.opsconsole.device.domain.ComputeConfigView;
import ffdd.opsconsole.device.domain.DatacenterReferenceCount;
import ffdd.opsconsole.device.domain.PlatformComputeConfigView;
import ffdd.opsconsole.device.domain.DeviceCatalogRepository;
import ffdd.opsconsole.device.domain.DeviceDatacenterView;
import ffdd.opsconsole.device.domain.DeviceGenerationGateView;
import ffdd.opsconsole.device.domain.DeviceOrderDetailView;
import ffdd.opsconsole.device.domain.DeviceOrderFacts;
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
import ffdd.opsconsole.device.dto.ComputeConfigBatchResponse;
import ffdd.opsconsole.device.dto.ComputeConfigBatchUpdateRequest;
import ffdd.opsconsole.device.dto.DatacenterOpsRequest;
import ffdd.opsconsole.device.dto.DeviceDatacenterUpsertRequest;
import ffdd.opsconsole.device.dto.DeviceE5ActionRequest;
import ffdd.opsconsole.device.dto.DeviceE5BatchRequest;
import ffdd.opsconsole.device.dto.DeviceEarlyAccessUpdateRequest;
import ffdd.opsconsole.device.dto.DeviceGenerationGateArchiveRequest;
import ffdd.opsconsole.device.dto.DeviceGenerationGatePatchRequest;
import ffdd.opsconsole.device.dto.DeviceGenerationGateUpsertRequest;
import ffdd.opsconsole.device.dto.DeviceOrderActionRequest;
import ffdd.opsconsole.device.dto.DeviceOrderQueryRequest;
import ffdd.opsconsole.device.dto.DeviceOrderStateRequest;
import ffdd.opsconsole.device.dto.DevicePhaseArchiveRequest;
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
import ffdd.opsconsole.device.dto.E2PhoneTierConfigUpdateRequest;
import ffdd.opsconsole.device.dto.E2TaskPricingUpdateRequest;
import ffdd.opsconsole.device.dto.E3ConfigUpdateRequest;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.finance.facade.E4OrderRefundSettlementFacade;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsDeviceService implements ffdd.opsconsole.platform.domain.AuditReplayable {
    private static final Set<String> RESTORABLE_STATUSES = Set.of("RECYCLED", "DEACTIVATED", "INACTIVE", "RETIRED");
    private static final Set<String> SKU_STATUSES = Set.of("on", "off", "pending");
    private static final Set<String> SKU_TIERS = Set.of("Entry", "Pro", "Flagship", "Share");
    private static final Set<String> SKU_LIFECYCLES = Set.of("active", "legacy");
    private static final Set<String> REVIEW_STATUSES = Set.of("published", "hidden");
    private static final Set<String> TASK_STATUSES = Set.of("active", "paused", "inactive");
    private static final Set<String> TASK_UNITS = Set.of("/job", "/1k", "/min");
    private static final Set<String> TASK_REQUIREMENTS = Set.of("手机+", "S1+", "需 NexionBox Pro", "需 NexionRack");
    private static final List<String> E2_TASK_CLASS_ORDER = List.of("IG", "VG", "LL", "FT", "EM", "SP");
    private static final Set<String> TASK_CLASSES = Set.copyOf(E2_TASK_CLASS_ORDER);
    private static final Map<String, String> E2_CANONICAL_TASK_ID = Map.of(
            "LL", "TK-1", "SP", "TK-2", "IG", "TK-3",
            "VG", "TK-4", "FT", "TK-5", "EM", "TK-6");
    private static final Set<String> TASK_KILL_INIT_STATES = Set.of("派发中", "已 kill", "限流中");
    private static final String E2_QUEUE_SATURATION_KEY = "E.task.queueSaturation";
    private static final Set<String> DATACENTER_STATUSES = Set.of("active", "maintenance", "disabled");
    private static final Set<String> ORDER_TERMINAL_STATES = Set.of(
            "payment_failed", "expired", "provisioning_failed", "chargeback");
    private static final Set<String> ORDER_FINAL_STATES = Set.of(
            "activated", "refunded", "cancelled", "payment_failed", "expired", "provisioning_failed", "chargeback");
    private static final Set<String> ORDER_REFUNDABLE_STATES = Set.of("paid", "provisioning", "activated");
    private static final Set<String> ORDER_MAIN_STATES = Set.of("placed", "paid", "provisioning", "activated");
    private static final Map<String, Set<String>> ORDER_TRANSITIONS = Map.of(
            "placed", Set.of("paid", "payment_failed", "expired", "cancelled"),
            "paid", Set.of("provisioning", "refunded", "chargeback"),
            "provisioning", Set.of("activated", "provisioning_failed", "refunded"),
            "activated", Set.of("refunded"));
    private static final Set<String> TRADEIN_OPERATIONS = Set.of("recycle", "replace", "deactivate");
    private static final String CURRENT_MONTH_KEY = "growth.phase.current_month";
    private static final String E1_PHASE_SCOPE = "E1";
    private static final int E5_MAX_DEVICES_PER_USER = 6;
    private static final int SKU_IMAGE_ASSET_ID_MAX_LENGTH = 512;
    private static final int SKU_IMAGE_OBJECT_KEY_MAX_LENGTH = 255;
    private static final int SKU_IMAGE_PREVIEW_URL_MAX_LENGTH = 4096;
    private static final Pattern E1_GENERATION_ID = Pattern.compile("^[a-z0-9-]{1,80}$");
    private static final Set<String> E1_GATE_FIELDS = Set.of(
            "releaseMonth",
            "phase",
            "eligibility",
            "phaseOffset",
            "forceUnlock");
    private static final Set<String> E1_GATE_STATUSES = Set.of("active", "archived");
    private static final Set<String> E1_PHASE_STATUSES = Set.of("active", "archived");
    private static final Set<String> E3_CONFIG_KEYS = Set.of(
            "stageEarlyEnd",
            "stageMidEnd",
            "cycleMonths",
            "taskLockS1",
            "taskLockPro",
            "taskLockRack",
            "eligibility",
            "promoMult",
            "promoCooldownDays",
            "promoMaxPerSession",
            "promoDelaySeconds",
            "promoMinAgeDays",
            "promoRoutes",
            "inventorySoftMax",
            "capacityBand1DeltaPct",
            "capacityBand2DeltaPct",
            "capacityBand3DeltaPct",
            "capacityFloorPct",
            "capacitySubsidyDays",
            "capacityApplyToPhone",
            "capacityApplyToCloudShare",
            "capacityApplyToPcGpu",
            "capacityApplyToS1",
            "capacityApplyToPro",
            "capacityApplyToProV2",
            "capacityApplyToRackP1",
            "capacityApplyToRackP2",
            "tradeinEnabled",
            "tradeinLadderCut1",
            "tradeinLadderCut2",
            "tradeinLadderCut3",
            "tradeinLadderCut4",
            "tradeinLadderCredit1",
            "tradeinLadderCredit2",
            "tradeinLadderCredit3",
            "tradeinLadderCredit4",
            "tradeinLadderCredit5",
            "tradeinRequireHigherPrice",
            "tradeinMaxDevicesPerOrder",
            "earlyAccessEnabled",
            "earlyAccessLeadDays");
    private static final Set<String> E3_RETIRED_CONFIG_KEYS = Set.of(
            "degradeEarly", "degradeMid", "degradeLate", "minEfficiency", "salvagePct", "minHoldingMonths");
    private final DeviceOpsRepository deviceRepository;
    private final DeviceCatalogRepository catalogRepository;
    private final PlatformConfigFacade configFacade;
    private final TreasuryLedgerPostingFacade ledgerPostingFacade;
    private final E4OrderRefundSettlementFacade refundSettlementFacade;
    private final TreasuryCoverageFacade coverageFacade;
    private final AuditLogService auditLogService;
    private final AdminIdempotencyService idempotencyService;
    private final EventOutboxService outboxService;
    private final Clock clock;
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;
    private final ffdd.opsconsole.platform.mapper.AuditObjectLockMapper lockMapper;

    public ApiResult<Map<String, Object>> overview() {
        Map<String, Object> response = new LinkedHashMap<>(deviceRepository.overviewCounters());
        response.put("domain", "E");
        response.put("service", "nexion-backend");
        response.put("generatedAt", LocalDateTime.now(clock));
        response.put("maxDevicesPerUser", E5_MAX_DEVICES_PER_USER);
        response.put("sources", List.of(
                "nx_user_device",
                "nx_user_device_runtime",
                "nx_compute_datacenter",
                "nx_compute_dc_ops_state"));
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
        DeviceSkuUpsertRequest writeRequest = normalizeSkuPhaseRequest(request);
        return e1Idempotent("E1_SKU_CREATE", idempotencyKey, "", writeRequest, () -> {
        String skuId = normalizeSkuId(writeRequest.skuId(), writeRequest.name());
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("E", "device_sku", skuId) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        if (catalogRepository.findSku(skuId).isPresent()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SKU_ALREADY_EXISTS");
        }
        if ("on".equals(normalizeSkuStatus(writeRequest.status()))) {
            ApiResult<DeviceSkuView> listingGuard = requireE1SkuListingAllowed(skuId, writeRequest.unlockPhase());
            if (listingGuard != null) {
                return listingGuard;
            }
        }
        LocalDateTime changedAt = LocalDateTime.now(clock);
        DeviceSkuView created = catalogRepository.createSku(skuId, writeRequest, changedAt);
        if ("on".equals(created.status())) {
            publishE1SkuLifecycleEvent(created.skuId(), "admin.product_listed", e1SkuStatusEvent(
                    created.skuId(), "absent", created.status(), writeRequest.operator(), writeRequest.reason()));
        }
        auditRequired("E1_SKU_CREATED", "DEVICE_SKU", created.skuId(), writeRequest.operator(), detail(
                "skuId", created.skuId(),
                "name", created.name(),
                "status", created.status(),
                "reason", writeRequest.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(created);
        });
    }

    public ApiResult<DeviceSkuView> updateSku(String skuId, String idempotencyKey, DeviceSkuUpsertRequest request) {
        ApiResult<DeviceSkuView> guard = requireSkuCommand(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        DeviceSkuUpsertRequest writeRequest = normalizeSkuPhaseRequest(request);
        return e1Idempotent("E1_SKU_UPDATE", idempotencyKey, skuId, writeRequest, () -> {
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("E", "device_sku", skuId) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        String normalized = normalizeId(skuId);
        if (!StringUtils.hasText(normalized)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SKU_ID_REQUIRED");
        }
        DeviceSkuView before = catalogRepository.findSku(normalized).orElse(null);
        if (before == null) {
            return ApiResult.fail(404, "SKU_NOT_FOUND");
        }
        String nextStatus = normalizeSkuStatus(writeRequest.status());
        if (!"on".equals(before.status()) && "on".equals(nextStatus)) {
            ApiResult<DeviceSkuView> listingGuard = requireE1SkuListingAllowed(normalized, writeRequest.unlockPhase());
            if (listingGuard != null) {
                return listingGuard;
            }
        }
        LocalDateTime changedAt = LocalDateTime.now(clock);
        DeviceSkuView updated = catalogRepository.updateSku(normalized, writeRequest, changedAt).orElse(before);
        if (!"on".equals(before.status()) && "on".equals(updated.status())) {
            publishE1SkuLifecycleEvent(normalized, "admin.product_listed", e1SkuStatusEvent(
                    normalized, before.status(), updated.status(), writeRequest.operator(), writeRequest.reason()));
        } else if ("on".equals(before.status()) && !"on".equals(updated.status())) {
            publishE1SkuLifecycleEvent(normalized, "admin.product_unlisted", e1SkuStatusEvent(
                    normalized, before.status(), updated.status(), writeRequest.operator(), writeRequest.reason()));
        }
        if (before.price() != null && updated.price() != null && before.price().compareTo(updated.price()) != 0) {
            publishE1SkuLifecycleEvent(normalized, "admin.product_price_changed", detail(
                    "sku_key", normalized,
                    "scope", "price",
                    "field", "price",
                    "before", before.price(),
                    "after", updated.price(),
                    "effective_at", changedAt.toString(),
                    "operator", writeRequest.operator(),
                    "reason", writeRequest.reason().trim()));
        }
        auditRequired("E1_SKU_UPDATED", "DEVICE_SKU", normalized, writeRequest.operator(), detail(
                "skuId", normalized,
                "beforeName", before.name(),
                "afterName", updated.name(),
                "beforePrice", before.price(),
                "afterPrice", updated.price(),
                "reason", writeRequest.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
        });
    }

    public ApiResult<DeviceSkuView> updateSkuStatus(String skuId, String idempotencyKey, DeviceSkuStatusRequest request) {
        ApiResult<Map<String, Object>> guard = requireE1Command(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return ApiResult.fail(guard.getCode(), guard.getMessage());
        }
        DeviceSkuStatusRequest trustedRequest = new DeviceSkuStatusRequest(
                request.status(), request.reason(), operator(request.operator()));
        return e1Idempotent("E1_SKU_STATUS", idempotencyKey, skuId, trustedRequest, () -> {
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("E", "device_sku", skuId) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        String status = normalizeSkuStatus(trustedRequest.status());
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
        if ("on".equals(status)) {
            ApiResult<DeviceSkuView> listingGuard = requireE1SkuListingAllowed(normalized);
            if (listingGuard != null) {
                return listingGuard;
            }
        }
        DeviceSkuView updated = catalogRepository.updateSkuStatus(normalized, status, LocalDateTime.now(clock)).orElse(before);
        if ("on".equals(status)) {
            publishE1SkuLifecycleEvent(normalized, "admin.product_listed", e1SkuStatusEvent(
                    normalized, before.status(), updated.status(), trustedRequest.operator(), trustedRequest.reason()));
        } else if ("on".equals(before.status())) {
            publishE1SkuLifecycleEvent(normalized, "admin.product_unlisted", e1SkuStatusEvent(
                    normalized, before.status(), updated.status(), trustedRequest.operator(), trustedRequest.reason()));
        }
        auditRequired("E1_SKU_STATUS_CHANGED", "DEVICE_SKU", normalized, trustedRequest.operator(), detail(
                "skuId", normalized,
                "fromStatus", before.status(),
                "toStatus", status,
                "reason", trustedRequest.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
        });
    }

    public ApiResult<Map<String, Object>> deleteSku(String skuId, String idempotencyKey, DeviceSkuStatusRequest request) {
        ApiResult<Map<String, Object>> guard = requireE1Command(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        DeviceSkuStatusRequest trustedRequest = new DeviceSkuStatusRequest(
                request.status(), request.reason(), operator(request.operator()));
        return e1Idempotent("E1_SKU_DELETE", idempotencyKey, skuId, trustedRequest, () -> {
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("E", "device_sku", skuId) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        String normalized = normalizeId(skuId);
        DeviceSkuView before = catalogRepository.findSku(normalized).orElse(null);
        if (before == null) {
            return ApiResult.fail(404, "SKU_NOT_FOUND");
        }
        catalogRepository.softDeleteSku(normalized, LocalDateTime.now(clock));
        if ("on".equals(before.status())) {
            publishE1SkuLifecycleEvent(normalized, "admin.product_unlisted", e1SkuStatusEvent(
                    normalized, before.status(), "deleted", trustedRequest.operator(), trustedRequest.reason()));
        }
        auditRequired("E1_SKU_DELETED", "DEVICE_SKU", normalized, trustedRequest.operator(), detail(
                "skuId", normalized,
                "name", before.name(),
                "reason", trustedRequest.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(detail("skuId", normalized, "deleted", true));
        });
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
        return e1Idempotent("E1_REVIEW_CREATE", idempotencyKey, "", request, () -> {
        String reviewId = nextReviewId();
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("E", "device_review", reviewId) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        DeviceReviewView created = catalogRepository.createReview(reviewId, request, LocalDateTime.now(clock));
        auditRequired("E1_REVIEW_CREATED", "DEVICE_REVIEW", created.reviewId(), request.operator(), detail(
                "reviewId", created.reviewId(),
                "skuId", created.skuId(),
                "status", created.status(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(created);
        });
    }

    public ApiResult<DeviceReviewView> updateReview(String reviewId, String idempotencyKey, DeviceReviewUpsertRequest request) {
        ApiResult<DeviceReviewView> guard = requireReviewCommand(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        return e1Idempotent("E1_REVIEW_UPDATE", idempotencyKey, reviewId, request, () -> {
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("E", "device_review", reviewId) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        String normalized = normalizeId(reviewId);
        DeviceReviewView before = catalogRepository.findReview(normalized).orElse(null);
        if (before == null) {
            return ApiResult.fail(404, "REVIEW_NOT_FOUND");
        }
        DeviceReviewView updated = catalogRepository.updateReview(normalized, request, LocalDateTime.now(clock)).orElse(before);
        auditRequired("E1_REVIEW_UPDATED", "DEVICE_REVIEW", normalized, request.operator(), detail(
                "reviewId", normalized,
                "skuId", updated.skuId(),
                "beforeStatus", before.status(),
                "afterStatus", updated.status(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
        });
    }

    public ApiResult<DeviceReviewView> updateReviewStatus(String reviewId, String idempotencyKey, DeviceReviewStatusRequest request) {
        ApiResult<Map<String, Object>> guard = requireE1Command(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return ApiResult.fail(guard.getCode(), guard.getMessage());
        }
        return e1Idempotent("E1_REVIEW_STATUS", idempotencyKey, reviewId, request, () -> {
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("E", "device_review", reviewId) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
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
        auditRequired("E1_REVIEW_STATUS_CHANGED", "DEVICE_REVIEW", normalized, request.operator(), detail(
                "reviewId", normalized,
                "fromStatus", before.status(),
                "toStatus", status,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
        });
    }

    public ApiResult<Map<String, Object>> deleteReview(String reviewId, String idempotencyKey, DeviceReviewStatusRequest request) {
        ApiResult<Map<String, Object>> guard = requireE1Command(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        return e1Idempotent("E1_REVIEW_DELETE", idempotencyKey, reviewId, request, () -> {
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("E", "device_review", reviewId) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        String normalized = normalizeId(reviewId);
        DeviceReviewView before = catalogRepository.findReview(normalized).orElse(null);
        if (before == null) {
            return ApiResult.fail(404, "REVIEW_NOT_FOUND");
        }
        catalogRepository.softDeleteReview(normalized, LocalDateTime.now(clock));
        auditRequired("E1_REVIEW_DELETED", "DEVICE_REVIEW", normalized, request.operator(), detail(
                "reviewId", normalized,
                "skuId", before.skuId(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(detail("reviewId", normalized, "deleted", true));
        });
    }

    @Transactional
    public ApiResult<PageResult<DeviceTaskView>> tasks(DeviceTaskQueryRequest request) {
        PageResult<DeviceTaskView> page = catalogRepository.pageTasks(request);
        return ApiResult.ok(page);
    }

    /** E6 算力与设备配置只读聚合:从 nx_config_item 读取覆盖值,缺失或非法时回落到注册表默认。 */
    public ApiResult<ComputeConfigView> computeConfig() {
        var flags = ComputeConfigRegistry.FLAGS.stream()
                .map(f -> new ComputeConfigView.FlagView(f.key(), f.label(), f.desc(), readFlag(f), f.frontendEffect())).toList();
        var coeffs = ComputeConfigRegistry.COEFFICIENTS.stream()
                .map(c -> new ComputeConfigView.CoeffView(c.key(), c.label(),
                        readComputeVal(ComputeConfigRegistry.coeffKey(c.key()), c.defaultVal()), c.unit(), c.desc(), c.frontendEffect())).toList();
        var yields = ComputeConfigRegistry.YIELD_ESTIMATE.stream()
                .map(y -> new ComputeConfigView.YieldView(y.key(), y.label(),
                        readComputeVal(ComputeConfigRegistry.yieldKey(y.key()), y.defaultVal()), y.unit())).toList();
        var tiers = ComputeConfigRegistry.GPU_TIERS.stream()
                .map(t -> new ComputeConfigView.GpuTierView(t.id(),
                        readComputeVal(ComputeConfigRegistry.gpuTierKey(t.id(), "label"), t.label()),
                        t.desc(), t.defaultModel(),
                        readComputeVal(ComputeConfigRegistry.gpuTierKey(t.id(), "tops"), t.defaultTops()),
                        readKeywords(t))).toList();
        var dl = new ComputeConfigView.DownloadView(
                readComputeVal(ComputeConfigRegistry.downloadKey("url"), ""),
                readComputeVal(ComputeConfigRegistry.downloadKey("zhTitle"), "电脑显卡算力共享"),
                readComputeVal(ComputeConfigRegistry.downloadKey("zhGuide"), "下载桌面客户端,使用同一账号登录,连接后电脑会出现在设备仓库中。"),
                readComputeVal(ComputeConfigRegistry.downloadKey("enTitle"), "Computer GPU share"),
                readComputeVal(ComputeConfigRegistry.downloadKey("enGuide"), "Download the desktop client, sign in with the same account, and the computer appears in device inventory after connection."));
        return ApiResult.ok(new ComputeConfigView("E6", flags, coeffs, yields, tiers, dl, List.of("nx_config_item:E.compute.*")));
    }

    /** App/H5 登录前可读的唯一 E6 服务端配置投影。 */
    public ApiResult<PlatformComputeConfigView> platformComputeConfig() {
        ComputeConfigView compute = computeConfig().getData();
        boolean enabled = compute.flags().stream()
                .filter(flag -> "computeShareEnabled".equals(flag.key()))
                .map(ComputeConfigView.FlagView::enabled)
                .findFirst().orElse(false);
        return ApiResult.ok(new PlatformComputeConfigView(
                new PlatformComputeConfigView.FeatureFlags(enabled),
                new PlatformComputeConfigView.OnlineBonus(
                        coefficientValue(compute, "h5BaseFactor", "0.6"),
                        coefficientValue(compute, "continuityFullHours", "2")),
                compute,
                LocalDateTime.now(clock).toString()));
    }

    /** E6 单参数写只允许由 A2 批准后的 replay 调用。 */
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
        if (!A2ReplayContext.isReplaying()) {
            return ApiResult.fail(409, "A2_CONFIRMATION_REQUIRED");
        }
        String trustedOperator = operator(request.operator());
        ComputeConfigParamUpdateRequest trusted = new ComputeConfigParamUpdateRequest(
                value, request.reason().trim(), trustedOperator);
        return deviceIdempotent("E6_COMPUTE_CONFIG_UPDATE", idempotencyKey, paramKey, trusted, () -> {
            String before = configFacade.activeValueForUpdate(paramKey).orElse("");
            if (before.equals(value)) {
                return ApiResult.fail(409, "E6_VALUE_UNCHANGED");
            }
            configFacade.upsertAdminValue(paramKey, value, "STRING", ComputeConfigRegistry.CONFIG_GROUP, "E6 compute config");
            String eventType = computeEventType(paramKey);
            outboxService.publish("E6_COMPUTE_CONFIG", paramKey, eventType, detail(
                    "param_key", paramKey, "before", before, "after", value,
                    "operator", trustedOperator, "reason", trusted.reason(), "ts", clock.millis()));
            auditRequired(eventType, "E6_COMPUTE_CONFIG", paramKey, trustedOperator, detail(
                    "paramKey", paramKey, "before", before, "after", value,
                    "reason", trusted.reason(), "idempotencyKey", idempotencyKey.trim()));
            return ApiResult.ok(new ComputeConfigParamResponse(paramKey, value, LocalDateTime.now(clock).toString()));
        });
    }

    /** E6 多参数变更先完整校验，再在一个幂等事务内提交，禁止半成功。 */
    @Transactional
    public ApiResult<ComputeConfigBatchResponse> updateComputeConfigBatch(
            String idempotencyKey, ComputeConfigBatchUpdateRequest request) {
        ApiResult<Map<String, Object>> command = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (command != null) {
            return ApiResult.fail(command.getCode(), command.getMessage());
        }
        if (request.values() == null || request.values().isEmpty() || request.values().size() > 32) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COMPUTE_BATCH_SIZE_INVALID");
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : request.values().entrySet()) {
            String key = normalizeText(entry.getKey());
            String value = normalizeText(entry.getValue());
            if (!ComputeConfigRegistry.isComputeParamKey(key)) {
                return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COMPUTE_PARAM_KEY_INVALID");
            }
            String error = validateComputeValue(key, value);
            if (error != null) {
                return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), error);
            }
            normalized.put(key, value);
        }
        if (!A2ReplayContext.isReplaying()) {
            return ApiResult.fail(409, "A2_CONFIRMATION_REQUIRED");
        }
        String trustedOperator = operator(request.operator());
        ComputeConfigBatchUpdateRequest trusted = new ComputeConfigBatchUpdateRequest(
                Map.copyOf(normalized), request.reason().trim(), trustedOperator);
        return deviceIdempotent("E6_COMPUTE_CONFIG_BATCH", idempotencyKey, "batch", trusted, () -> {
            Map<String, String> before = new LinkedHashMap<>();
            Map<String, String> changed = new LinkedHashMap<>();
            normalized.forEach((key, value) -> {
                String current = configFacade.activeValueForUpdate(key).orElse("");
                before.put(key, current);
                if (!current.equals(value)) {
                    changed.put(key, value);
                }
            });
            if (changed.isEmpty()) {
                return ApiResult.fail(409, "E6_VALUE_UNCHANGED");
            }
            changed.forEach((key, value) -> configFacade.upsertAdminValue(
                    key, value, "STRING", ComputeConfigRegistry.CONFIG_GROUP, "E6 compute config batch"));
            outboxService.publish("E6_COMPUTE_CONFIG", "batch", "compute.config_changed", detail(
                    "keys", List.copyOf(changed.keySet()), "before", before, "after", changed,
                    "operator", trustedOperator, "reason", trusted.reason(), "ts", clock.millis()));
            auditRequired("compute.config_changed", "E6_COMPUTE_CONFIG", "batch", trustedOperator, detail(
                    "keys", List.copyOf(changed.keySet()), "before", before, "after", changed,
                    "reason", trusted.reason(), "idempotencyKey", idempotencyKey.trim()));
            return ApiResult.ok(new ComputeConfigBatchResponse(
                    Map.copyOf(changed), LocalDateTime.now(clock).toString()));
        });
    }

    private String validateComputeValue(String paramKey, String value) {
        if (paramKey.equals(ComputeConfigRegistry.flagKey("computeShareEnabled"))) {
            return Set.of("on", "off").contains(value) ? null : "COMPUTE_FLAG_INVALID";
        }
        if (paramKey.equals(ComputeConfigRegistry.coeffKey("h5BaseFactor"))) {
            BigDecimal v = parseComputeNumber(value);
            return (v != null && v.signum() > 0 && v.compareTo(BigDecimal.ONE) <= 0) ? null : "COMPUTE_COEFF_INVALID";
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
            return value.length() > 48 ? "COMPUTE_KEYWORD_INVALID" : null;
        }
        if (paramKey.equals(ComputeConfigRegistry.downloadKey("url"))) {
            if (value.isBlank()) {
                return null;
            }
            return (value.length() <= 300 && value.matches("^https://[^\\s]+$")) ? null : "COMPUTE_URL_INVALID";
        }
        if (paramKey.startsWith(ComputeConfigRegistry.PARAM_PREFIX + "download.")) {
            return value.isBlank() || value.length() > 320 ? "COMPUTE_DOWNLOAD_TEXT_INVALID" : null;
        }
        return "COMPUTE_PARAM_KEY_INVALID";
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
        ApiResult<Map<String, Object>> command = requireE1Command(idempotencyKey, request == null ? null : request.reason());
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
        DevicePhoneTierRewardUpdateRequest trusted = new DevicePhoneTierRewardUpdateRequest(
                request.dailyUsdt(), request.dailyNex(), request.reason().trim(), operator(request.operator()));
        return deviceIdempotent("E2_PHONE_TIER_UPDATE", idempotencyKey, String.valueOf(tier), trusted, () -> {
            if (!A2ReplayContext.isReplaying()
                    && lockMapper.countActiveByTarget("E", "phone_tier", String.valueOf(tier)) > 0) {
                return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
            }
            DevicePhoneTierRewardView before = catalogRepository.findPhoneTierReward(tier).orElse(null);
            if (before == null) {
                return ApiResult.fail(404, "PHONE_TIER_NOT_FOUND");
            }
            BigDecimal nextUsdt = trusted.dailyUsdt() == null ? before.dailyUsdt() : trusted.dailyUsdt();
            BigDecimal nextNex = trusted.dailyNex() == null ? before.dailyNex() : trusted.dailyNex();
            List<DevicePhoneTierRewardView> tiers = catalogRepository.listPhoneTierRewards();
            if (!isMonotonicPhoneTier(tiers, tier, nextUsdt, DevicePhoneTierRewardView::dailyUsdt)
                    || !isMonotonicPhoneTier(tiers, tier, nextNex, DevicePhoneTierRewardView::dailyNex)) {
                return ApiResult.fail(400, "PHONE_TIER_MONOTONICITY_INVALID");
            }
            if ((nextUsdt.compareTo(before.dailyUsdt()) > 0 || nextNex.compareTo(before.dailyNex()) > 0)
                    && coverageBelowRedline()) {
                return ApiResult.fail(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus(),
                        OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
            }
            DevicePhoneTierRewardView updated = catalogRepository
                    .updatePhoneTierReward(tier, trusted, LocalDateTime.now(clock))
                    .orElse(before);
            auditRequired("E2_PHONE_TIER_REWARD_CHANGED", "PHONE_TIER_REWARD", String.valueOf(tier),
                    trusted.operator(), detail(
                            "tier", tier,
                            "beforeDailyUsdt", before.dailyUsdt(),
                            "afterDailyUsdt", updated.dailyUsdt(),
                            "beforeDailyNex", before.dailyNex(),
                            "afterDailyNex", updated.dailyNex(),
                            "reason", trusted.reason(),
                            "idempotencyKey", idempotencyKey.trim()));
            return ApiResult.ok(updated);
        });
    }

    public ApiResult<DeviceTaskView> createTask(String idempotencyKey, DeviceTaskUpsertRequest request) {
        ApiResult<DeviceTaskView> guard = requireTaskCommand(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        DeviceTaskUpsertRequest trusted = trustedTaskRequest(request);
        return deviceIdempotent("E2_TASK_CREATE", idempotencyKey, "", trusted, () -> {
            String taskId = nextTaskId();
            if (!A2ReplayContext.isReplaying()
                    && lockMapper.countActiveByTarget("E", "device_task", taskId) > 0) {
                return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
            }
            DeviceTaskView created = catalogRepository.createTask(taskId, trusted, LocalDateTime.now(clock));
            auditRequired("E2_TASK_CREATED", "DEVICE_TASK", created.taskId(), trusted.operator(), detail(
                    "taskId", created.taskId(), "name", created.name(), "price", created.price(),
                    "taskClass", created.taskClass(), "model", created.model(),
                    "minReward", created.minReward(), "maxReward", created.maxReward(),
                    "minVram", created.minVram(), "killInit", created.killInit(),
                    "reason", trusted.reason(), "idempotencyKey", idempotencyKey.trim()));
            return ApiResult.ok(created);
        });
    }

    public ApiResult<DeviceTaskView> updateTask(String taskId, String idempotencyKey, DeviceTaskUpsertRequest request) {
        ApiResult<DeviceTaskView> guard = requireTaskCommand(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        DeviceTaskUpsertRequest trusted = trustedTaskRequest(request);
        return deviceIdempotent("E2_TASK_UPDATE", idempotencyKey, taskId, trusted, () -> {
            if (!A2ReplayContext.isReplaying()
                    && lockMapper.countActiveByTarget("E", "device_task", taskId) > 0) {
                return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
            }
            String normalized = normalizeId(taskId);
            DeviceTaskView before = catalogRepository.findTask(normalized).orElse(null);
            if (before == null) {
                return ApiResult.fail(404, "TASK_NOT_FOUND");
            }
            DeviceTaskView updated = catalogRepository.updateTask(normalized, trusted, LocalDateTime.now(clock)).orElse(before);
            auditRequired("E2_TASK_UPDATED", "DEVICE_TASK", normalized, trusted.operator(), detail(
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
                "reason", trusted.reason(),
                "idempotencyKey", idempotencyKey.trim()));
            return ApiResult.ok(updated);
        });
    }

    public ApiResult<DeviceTaskView> updateTaskPrice(String taskId, String idempotencyKey, DeviceTaskPriceRequest request) {
        ApiResult<Map<String, Object>> command = requireE1Command(idempotencyKey, request == null ? null : request.reason());
        if (command != null) {
            return ApiResult.fail(command.getCode(), command.getMessage());
        }
        DeviceTaskPriceRequest trusted = new DeviceTaskPriceRequest(
                request.price(), request.reason().trim(), operator(request.operator()));
        return deviceIdempotent("E2_TASK_PRICE", idempotencyKey, taskId, trusted, () -> {
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("E", "device_task", taskId) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        if (trusted.price() == null || trusted.price().compareTo(BigDecimal.ZERO) <= 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TASK_PRICE_INVALID");
        }
        String normalized = normalizeId(taskId);
        DeviceTaskView before = catalogRepository.findTask(normalized).orElse(null);
        if (before == null) {
            return ApiResult.fail(404, "TASK_NOT_FOUND");
        }
        DeviceTaskView updated = catalogRepository.updateTaskPrice(normalized, trusted.price(), LocalDateTime.now(clock)).orElse(before);
        auditRequired("E2_TASK_PRICE_CHANGED", "DEVICE_TASK", normalized, trusted.operator(), detail(
                "taskId", normalized,
                "beforePrice", before.price(),
                "afterPrice", updated.price(),
                "reason", trusted.reason(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
        });
    }

    public ApiResult<DeviceTaskView> updateTaskStatus(String taskId, String idempotencyKey, DeviceTaskStatusRequest request) {
        ApiResult<Map<String, Object>> command = requireE1Command(idempotencyKey, request == null ? null : request.reason());
        if (command != null) {
            return ApiResult.fail(command.getCode(), command.getMessage());
        }
        DeviceTaskStatusRequest trusted = new DeviceTaskStatusRequest(
                request.status(), request.reason().trim(), operator(request.operator()));
        return deviceIdempotent("E2_TASK_STATUS", idempotencyKey, taskId, trusted, () -> {
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("E", "device_task", taskId) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        String status = normalizeTaskStatus(trusted.status());
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
        auditRequired("E2_TASK_STATUS_CHANGED", "DEVICE_TASK", normalized, trusted.operator(), detail(
                "taskId", normalized,
                "fromStatus", before.status(),
                "toStatus", updated.status(),
                "reason", trusted.reason(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
        });
    }

    public ApiResult<Map<String, Object>> deleteTask(String taskId, String idempotencyKey, DeviceTaskStatusRequest request) {
        ApiResult<Map<String, Object>> command = requireE1Command(idempotencyKey, request == null ? null : request.reason());
        if (command != null) {
            return command;
        }
        DeviceTaskStatusRequest trusted = new DeviceTaskStatusRequest(
                request.status(), request.reason().trim(), operator(request.operator()));
        return deviceIdempotent("E2_TASK_DELETE", idempotencyKey, taskId, trusted, () -> {
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("E", "device_task", taskId) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
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
        auditRequired("E2_TASK_DELETED", "DEVICE_TASK", normalized, trusted.operator(), detail(
                "taskId", normalized,
                "name", before.name(),
                "reason", trusted.reason(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(detail("taskId", normalized, "deleted", true));
        });
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

    public ApiResult<DeviceOrderDetailView> order(String orderNo) {
        String normalizedOrderNo = normalizeId(orderNo);
        DeviceOrderView order = catalogRepository.findOrder(normalizedOrderNo).orElse(null);
        DeviceOrderFacts facts = catalogRepository.findOrderFacts(normalizedOrderNo).orElse(null);
        if (order == null || facts == null) {
            return ApiResult.fail(404, "ORDER_NOT_FOUND");
        }
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        BigDecimal current = coverage == null ? null : coverage.coverageRatio();
        BigDecimal redline = coverage == null ? null : coverage.redlinePct();
        BigDecimal projected = projectedCoverage(coverage, facts.amountUsdt());
        boolean refundAllowed = ORDER_REFUNDABLE_STATES.contains(normalizeOrderState(order.state()))
                && !refundCoverageBlocked(coverage, facts.amountUsdt());
        // 仅暴露当前真正可执行的渠道。原支付渠道需要 PSP 退款适配器，未接通前必须失败关闭，
        // 也不能在控制台给运营一个注定失败的选项。
        List<String> channels = List.of("WALLET");
        return ApiResult.ok(new DeviceOrderDetailView(
                order,
                facts.userId(),
                facts.quantity(),
                facts.orderType(),
                facts.subtotalUsdt(),
                facts.discountUsdt(),
                facts.paymentNo(),
                facts.paymentMethod(),
                facts.paymentStatus(),
                facts.orderStatus(),
                facts.activationStatus(),
                facts.deviceId(),
                facts.deviceInstanceNo(),
                catalogRepository.listOrderHistory(normalizedOrderNo),
                catalogRepository.listOrderFunding(normalizedOrderNo),
                current,
                redline,
                projected,
                refundAllowed,
                channels));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<DeviceOrderView> refundOrder(String orderNo, String idempotencyKey, DeviceOrderActionRequest request) {
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("E", "device_order", orderNo) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        ApiResult<Map<String, Object>> command = requireE1Command(
                idempotencyKey, request == null ? null : request.reason());
        if (command != null) {
            return ApiResult.fail(command.getCode(), command.getMessage());
        }
        String actor = operator(request.operator());
        DeviceOrderActionRequest trusted = new DeviceOrderActionRequest(
                request.terminalState(), request.refundChannel(), request.reason().trim(), actor);
        return deviceIdempotent("E4_ORDER_REFUND", idempotencyKey, normalizeId(orderNo), trusted,
                () -> transitionOrder(orderNo, idempotencyKey, trusted, "refunded", "E4_ORDER_REFUNDED"));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<DeviceOrderView> cancelOrder(String orderNo, String idempotencyKey, DeviceOrderActionRequest request) {
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("E", "device_order", orderNo) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        ApiResult<Map<String, Object>> command = requireE1Command(
                idempotencyKey, request == null ? null : request.reason());
        if (command != null) {
            return ApiResult.fail(command.getCode(), command.getMessage());
        }
        String actor = operator(request.operator());
        DeviceOrderActionRequest trusted = new DeviceOrderActionRequest(
                request.terminalState(), request.refundChannel(), request.reason().trim(), actor);
        return deviceIdempotent("E4_ORDER_CANCEL", idempotencyKey, normalizeId(orderNo), trusted,
                () -> transitionOrder(orderNo, idempotencyKey, trusted, "cancelled", "E4_ORDER_CANCELLED"));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<DeviceOrderView> terminalOrder(String orderNo, String idempotencyKey, DeviceOrderActionRequest request) {
        ApiResult<Map<String, Object>> command = requireE1Command(idempotencyKey, request == null ? null : request.reason());
        if (command != null) {
            return ApiResult.fail(command.getCode(), command.getMessage());
        }
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("E", "device_order", orderNo) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        String terminal = normalizeOrderState(request.terminalState());
        if (!ORDER_TERMINAL_STATES.contains(terminal)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "ORDER_TERMINAL_STATE_INVALID");
        }
        String actor = operator(request.operator());
        DeviceOrderActionRequest trusted = new DeviceOrderActionRequest(
                request.terminalState(), request.refundChannel(), request.reason().trim(), actor);
        return deviceIdempotent("E4_ORDER_TERMINAL", idempotencyKey, normalizeId(orderNo), trusted,
                () -> transitionOrder(orderNo, idempotencyKey, trusted, terminal, "E4_ORDER_TERMINALIZED"));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<DeviceOrderView> updateOrderState(String orderNo, String idempotencyKey, DeviceOrderStateRequest request) {
        ApiResult<Map<String, Object>> command = requireE1Command(idempotencyKey, request == null ? null : request.reason());
        if (command != null) {
            return ApiResult.fail(command.getCode(), command.getMessage());
        }
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("E", "device_order", orderNo) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        String toState = normalizeOrderState(request.state());
        if (!ORDER_MAIN_STATES.contains(toState)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "ORDER_STATE_INVALID");
        }
        String actor = operator(request.operator());
        DeviceOrderStateRequest trusted = new DeviceOrderStateRequest(toState, request.reason().trim(), actor);
        return deviceIdempotent("E4_ORDER_STATE", idempotencyKey, normalizeId(orderNo), trusted,
                () -> updateOrderStateInternal(orderNo, idempotencyKey, trusted));
    }

    private ApiResult<DeviceOrderView> updateOrderStateInternal(
            String orderNo, String idempotencyKey, DeviceOrderStateRequest request) {
        String toState = normalizeOrderState(request.state());
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
        LocalDateTime now = LocalDateTime.now(clock);
        DeviceOrderView updated = catalogRepository.updateOrderState(normalizedOrderNo, fromState, toState, now).orElse(null);
        if (updated == null) {
            return ApiResult.fail(409, "ORDER_STATE_CONFLICT");
        }
        catalogRepository.recordOrderHistory(normalizedOrderNo, fromState, toState, request.reason().trim(),
                request.operator(), idempotencyKey.trim(), now);
        auditRequired("E4_ORDER_STATE_CHANGED", "DEVICE_ORDER", normalizedOrderNo, request.operator(), detail(
                "orderNo", normalizedOrderNo,
                "fromState", fromState,
                "toState", toState,
                "amount", before.amount(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        publishOrderEvent("E4_ORDER_STATE_CHANGED", normalizedOrderNo, before, updated, request.operator());
        return ApiResult.ok(updated);
    }

    public ApiResult<Map<String, Object>> e1GenerationGates() {
        int platformMonth = currentPlatformMonth();
        List<DeviceGenerationGateView> gates = catalogRepository.listGenerationGates(false);
        Map<String, String> configValues = e1GateConfigValues(gates);
        List<DevicePhaseView> phases = e1PhaseDefs();
        GrowthRhythmSnapshot rhythm = GrowthRhythmSnapshot.from(configFacade, readTimeSeedPolicy);
        String currentPhase = currentE1PhaseId(platformMonth, phases);
        Map<String, Object> h1Rhythm = new LinkedHashMap<>(rhythm.summary());
        h1Rhythm.put("currentPhase", currentPhase);
        List<String> phaseOrder = phases.stream().map(DevicePhaseView::p).toList();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "E1");
        response.put("phaseOrder", phaseOrder);
        response.put("phases", phases);
        response.put("platformMonth", platformMonth);
        response.put("phaseCurrent", currentPhase);
        response.put("h1Rhythm", h1Rhythm);
        response.put("h1PhaseSource", rhythm.summary());
        response.put("releases", e1GenerationReleases(gates));
        response.put("configValues", configValues);
        response.put("allowedFields", List.of("releaseMonth", "phase", "eligibility", "phaseOffset", "forceUnlock"));
        response.put("sources", List.of(
                "nx_admin_phase_config",
                "nx_admin_device_generation_gate",
                "nx_config_item:" + CURRENT_MONTH_KEY,
                "H1 growth rhythm facade",
                "E5 eligibility"));
        return ApiResult.ok(response);
    }

    @Transactional
    public ApiResult<Map<String, Object>> createE1Phase(String idempotencyKey, DevicePhaseUpsertRequest request) {
        ApiResult<Map<String, Object>> guard = requireE1Command(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (request == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "E1_PHASE_REQUEST_REQUIRED");
        }
        return e1Idempotent("E1_PHASE_CREATE", idempotencyKey, "", request, () -> {
        String label = normalizePhaseLabel(request.label());
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("E", "device_phase", label) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
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
        auditRequired("E1_PHASE_CREATED", "DEVICE_PHASE", created.p(), request.operator(), detail(
                "id", created.p(),
                "label", created.label(),
                "meta", created.meta(),
                "skus", created.skus(),
                "sortOrder", created.sortOrder(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return e1GenerationGates();
        });
    }

    public ApiResult<Map<String, Object>> e2TaskPricing() {
        List<DeviceTaskView> all = catalogRepository
                .pageTasks(new DeviceTaskQueryRequest(null, null, null, 1L, 500L))
                .getRecords();
        BigDecimal queueSaturation = configFacade.activeValue(E2_QUEUE_SATURATION_KEY)
                .map(this::parseDecimalOrNull)
                .filter(value -> value.compareTo(BigDecimal.ZERO) >= 0 && value.compareTo(BigDecimal.ONE) <= 0)
                .orElse(new BigDecimal("0.35"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String taskClass : E2_TASK_CLASS_ORDER) {
            DeviceTaskView task = all.stream()
                    .filter(item -> E2_CANONICAL_TASK_ID.get(taskClass).equals(item.taskId()))
                    .findFirst()
                    .orElseGet(() -> all.stream()
                    .filter(item -> taskClass.equals(item.taskClass()))
                    .findFirst()
                    .orElse(null));
            if (task == null) {
                continue;
            }
            BigDecimal averageReward = task.minReward().add(task.maxReward())
                    .divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
            BigDecimal avgSec = e2AverageSeconds(taskClass);
            BigDecimal dailyPotential = BigDecimal.valueOf(86400)
                    .divide(avgSec, 8, RoundingMode.HALF_UP)
                    .multiply(queueSaturation)
                    .multiply(averageReward)
                    .setScale(4, RoundingMode.HALF_UP);
            rows.add(detail(
                    "taskId", task.taskId(),
                    "taskClass", task.taskClass(),
                    "taskName", task.name(),
                    "models", csv(task.model()),
                    "minReward", task.minReward(),
                    "maxReward", task.maxReward(),
                    "minVRAM", parseVram(task.minVram()),
                    "enabled", "active".equals(task.status()) && !"已 kill".equals(task.killInit()),
                    "activeAssignments", 0,
                    "avgSec", avgSec,
                    "dailyPotential", dailyPotential,
                    "effectiveAt", task.updatedAt()));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("taskClasses", rows);
        response.put("queueSaturation", queueSaturation);
        response.put("teaser", e2Teaser(rows));
        response.put("effectiveAt", rows.stream()
                .map(row -> row.get("effectiveAt"))
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(LocalDateTime.now(clock)));
        response.put("sources", List.of("nx_admin_device_task", "nx_config_item:" + E2_QUEUE_SATURATION_KEY));
        return ApiResult.ok(response);
    }

    @Transactional
    public ApiResult<Map<String, Object>> updateE2TaskPricing(
            String idempotencyKey, E2TaskPricingUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireE1Command(
                idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String trustedOperator = operator(request.operator());
        E2TaskPricingUpdateRequest trusted = new E2TaskPricingUpdateRequest(
                normalizeUpper(request.taskClass()), request.minReward(), request.maxReward(), request.minVram(),
                request.enabled(), request.queueSaturation(), request.reason().trim(), trustedOperator);
        return deviceIdempotent("E2_TASK_PRICING_UPDATE", idempotencyKey, trusted.taskClass(), trusted, () -> {
            if (trusted.queueSaturation() != null) {
                if (StringUtils.hasText(trusted.taskClass()) || trusted.minReward() != null || trusted.maxReward() != null
                        || trusted.minVram() != null || trusted.enabled() != null) {
                    return ApiResult.fail(400, "E2_SINGLE_ACTION_REQUIRED");
                }
                if (trusted.queueSaturation().compareTo(BigDecimal.ZERO) < 0
                        || trusted.queueSaturation().compareTo(BigDecimal.ONE) > 0) {
                    return ApiResult.fail(400, "QUEUE_SATURATION_INVALID");
                }
                BigDecimal before = configFacade.activeValueForUpdate(E2_QUEUE_SATURATION_KEY)
                        .map(this::parseDecimalOrNull).orElse(new BigDecimal("0.35"));
                if (before.compareTo(trusted.queueSaturation()) == 0) {
                    return ApiResult.fail(409, "E2_VALUE_UNCHANGED");
                }
                LocalDateTime effectiveAt = LocalDateTime.now(clock);
                configFacade.upsertAdminValue(E2_QUEUE_SATURATION_KEY,
                        trusted.queueSaturation().stripTrailingZeros().toPlainString(), "DECIMAL", "E2",
                        "E2 locked teaser global saturation");
                recordE2TaskPricingChange("GLOBAL", "QUEUE_SATURATION", before, trusted.queueSaturation(),
                        effectiveAt, trustedOperator, trusted.reason(), idempotencyKey);
                return ApiResult.ok(detail("effectiveAt", effectiveAt, "taskPricing", e2TaskPricing().getData()));
            }
            if (!TASK_CLASSES.contains(trusted.taskClass())) {
                return ApiResult.fail(400, "TASK_CLASS_INVALID");
            }
            int changedFields = (trusted.minReward() == null ? 0 : 1)
                    + (trusted.maxReward() == null ? 0 : 1)
                    + (trusted.minVram() == null ? 0 : 1)
                    + (trusted.enabled() == null ? 0 : 1);
            if (changedFields == 0 || (trusted.enabled() != null && changedFields > 1)) {
                return ApiResult.fail(400, "E2_SINGLE_ACTION_REQUIRED");
            }
            DeviceTaskView before = findE2TaskByClass(trusted.taskClass());
            if (before == null) {
                return ApiResult.fail(404, "TASK_CLASS_NOT_FOUND");
            }
            if (!A2ReplayContext.isReplaying()
                    && lockMapper.countActiveByTarget("E", "device_task", before.taskId()) > 0) {
                return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
            }
            BigDecimal nextMin = trusted.minReward() == null ? before.minReward() : trusted.minReward();
            BigDecimal nextMax = trusted.maxReward() == null ? before.maxReward() : trusted.maxReward();
            int nextVram = trusted.minVram() == null ? parseVram(before.minVram()) : trusted.minVram();
            boolean nextEnabled = trusted.enabled() == null
                    ? "active".equals(before.status()) && !"已 kill".equals(before.killInit())
                    : trusted.enabled();
            if (nextMin == null || nextMax == null || nextMin.compareTo(BigDecimal.ZERO) < 0
                    || nextMax.compareTo(BigDecimal.ZERO) < 0 || nextMin.compareTo(nextMax) > 0) {
                return ApiResult.fail(400, "TASK_REWARD_RANGE_INVALID");
            }
            if (nextVram < 0) {
                return ApiResult.fail(400, "TASK_MIN_VRAM_INVALID");
            }
            String field = trusted.minReward() != null ? "minReward"
                    : trusted.maxReward() != null ? "maxReward"
                    : trusted.minVram() != null ? "minVRAM" : "enabled";
            Object beforeValue = switch (field) {
                case "minReward" -> before.minReward();
                case "maxReward" -> before.maxReward();
                case "minVRAM" -> parseVram(before.minVram());
                default -> "active".equals(before.status()) && !"已 kill".equals(before.killInit());
            };
            Object afterValue = switch (field) {
                case "minReward" -> nextMin;
                case "maxReward" -> nextMax;
                case "minVRAM" -> nextVram;
                default -> nextEnabled;
            };
            if (String.valueOf(beforeValue).equals(String.valueOf(afterValue))) {
                return ApiResult.fail(409, "E2_VALUE_UNCHANGED");
            }
            DeviceTaskUpsertRequest write = new DeviceTaskUpsertRequest(
                    before.name(), before.price(), before.unit(), before.requirement(), before.saturation(),
                    nextEnabled ? "active" : "paused", before.taskClass(), before.model(), nextMin, nextMax,
                    nextVram + "GB", nextEnabled ? "派发中" : "已 kill", trusted.reason(), trustedOperator);
            LocalDateTime effectiveAt = LocalDateTime.now(clock);
            catalogRepository.updateTask(before.taskId(), write, effectiveAt);
            recordE2TaskPricingChange(trusted.taskClass(), field, beforeValue, afterValue,
                    effectiveAt, trustedOperator, trusted.reason(), idempotencyKey);
            return ApiResult.ok(detail("effectiveAt", effectiveAt, "taskPricing", e2TaskPricing().getData()));
        });
    }

    public ApiResult<Map<String, Object>> e2PhoneTiers() {
        List<Map<String, Object>> rows = catalogRepository.listPhoneTierRewards().stream()
                .map(tier -> detail("tier", tier.tier(), "name", tier.name(),
                        "baseRateUsdt", tier.dailyUsdt(), "baseRateNex", tier.dailyNex(),
                        "effectiveAt", tier.updatedAt()))
                .toList();
        return ApiResult.ok(detail("tiers", rows, "sources", List.of("nx_admin_phone_tier_reward")));
    }

    public ApiResult<Map<String, Object>> updateE2PhoneTier(
            String idempotencyKey, E2PhoneTierConfigUpdateRequest request) {
        if (request == null || request.tier() == null) {
            return ApiResult.fail(400, "PHONE_TIER_INVALID");
        }
        ApiResult<DevicePhoneTierRewardView> result = updatePhoneTierReward(request.tier(), idempotencyKey,
                new DevicePhoneTierRewardUpdateRequest(request.baseRateUsdt(), request.baseRateNex(),
                        request.reason(), request.operator()));
        if (result.getCode() != 0) {
            return ApiResult.fail(result.getCode(), result.getMessage());
        }
        return ApiResult.ok(detail("effectiveAt", result.getData().updatedAt(), "phoneTiers", e2PhoneTiers().getData()));
    }

    @Transactional
    public ApiResult<Map<String, Object>> patchE1Phase(String phaseId, String idempotencyKey, DevicePhaseUpsertRequest request) {
        ApiResult<Map<String, Object>> guard = requireE1Command(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (request == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "E1_PHASE_REQUEST_REQUIRED");
        }
        return e1Idempotent("E1_PHASE_PATCH", idempotencyKey, phaseId, request, () -> {
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("E", "device_phase", phaseId) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
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
        String nextStatus = request.status() == null ? before.status() : normalizePhaseStatus(request.status());
        if ("archived".equals(nextStatus) && !"archived".equals(before.status())) {
            ApiResult<Map<String, Object>> archiveGuard = requireE1PhaseArchivable(currentPhaseId);
            if (archiveGuard != null) {
                return archiveGuard;
            }
        }
        LocalDateTime now = LocalDateTime.now(clock);
        DevicePhaseView updated = catalogRepository.savePhase(
                E1_PHASE_SCOPE,
                currentPhaseId,
                nextLabel,
                request.meta() == null ? before.meta() : normalizeText(request.meta()),
                request.skus() == null ? before.skus() : normalizeText(request.skus()),
                request.sortOrder() == null ? before.sortOrder() : normalizePhaseSortOrder(request.sortOrder()),
                nextStatus,
                now);
        auditRequired("E1_PHASE_UPDATED", "DEVICE_PHASE", updated.p(), request.operator(), detail(
                "before", phaseSnapshot(before),
                "after", phaseSnapshot(updated),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return e1GenerationGates();
        });
    }

    @Transactional
    public ApiResult<Map<String, Object>> archiveE1Phase(String phaseId, String idempotencyKey, DevicePhaseArchiveRequest request) {
        ApiResult<Map<String, Object>> guard = requireE1Command(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        return e1Idempotent("E1_PHASE_ARCHIVE", idempotencyKey, phaseId, request, () -> {
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("E", "device_phase", phaseId) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        String normalized = matchConfiguredE1PhaseId(phaseId, true);
        DevicePhaseView before = catalogRepository.findPhase(E1_PHASE_SCOPE, normalized).orElse(null);
        if (before == null) {
            return ApiResult.fail(404, "E1_PHASE_NOT_FOUND");
        }
        ApiResult<Map<String, Object>> archiveGuard = requireE1PhaseArchivable(normalized);
        if (archiveGuard != null) {
            return archiveGuard;
        }
        catalogRepository.archivePhase(E1_PHASE_SCOPE, normalized, LocalDateTime.now(clock));
        auditRequired("E1_PHASE_ARCHIVED", "DEVICE_PHASE", normalized, request.operator(), detail(
                "phase", phaseSnapshot(before),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return e1GenerationGates();
        });
    }

    @Transactional
    public ApiResult<Map<String, Object>> createE1GenerationGate(String idempotencyKey, DeviceGenerationGateUpsertRequest request) {
        ApiResult<Map<String, Object>> guard = requireE1Command(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (request == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "E1_GATE_REQUEST_REQUIRED");
        }
        return e1Idempotent("E1_GATE_CREATE", idempotencyKey, request.skuId(), request, () -> {
        catalogRepository.backfillPhaseReferences(E1_PHASE_SCOPE, LocalDateTime.now(clock));
        String skuId = normalizeGenerationId(request.skuId());
        if (!StringUtils.hasText(skuId)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "E1_GATE_SKU_ID_INVALID");
        }
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("E", "device_generation_gate", skuId) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
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
        String phase = normalizeE1Phase(request.phase());
        boolean eligibility = Boolean.TRUE.equals(request.eligibility());
        boolean forceUnlock = Boolean.TRUE.equals(request.forceUnlock());
        ApiResult<Map<String, Object>> forceUnlockGuard =
                requireE1ForceUnlockTransition(false, forceUnlock, eligibility, phase);
        if (forceUnlockGuard != null) {
            return forceUnlockGuard;
        }
        DeviceGenerationGateView created = catalogRepository.saveGenerationGate(
                skuId,
                name,
                normalizeReleaseMonth(request.releaseMonth()),
                phase,
                BigDecimal.ZERO,
                eligibility,
                normalizePhaseOffset(request.phaseOffset() == null ? 0 : request.phaseOffset()),
                forceUnlock,
                normalizeGenerationGateStatus(request.status()),
                LocalDateTime.now(clock));
        auditRequired("E1_GENERATION_GATE_CREATED", "DEVICE_GENERATION_GATE", skuId, request.operator(), detail(
                "generationId", skuId,
                "name", created.name(),
                "releaseMonth", created.releaseMonth(),
                "phase", created.phase(),
                "eligibility", created.eligibility(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return e1GenerationGates();
        });
    }

    @Transactional
    public ApiResult<Map<String, Object>> patchE1GenerationGate(String skuId, String idempotencyKey, DeviceGenerationGatePatchRequest request) {
        ApiResult<Map<String, Object>> guard = requireE1Command(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (request == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "E1_GATE_REQUEST_REQUIRED");
        }
        return e1Idempotent("E1_GATE_PATCH", idempotencyKey, skuId, request, () -> {
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("E", "device_generation_gate", skuId) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        String normalized = normalizeGenerationId(skuId);
        catalogRepository.backfillPhaseReferences(E1_PHASE_SCOPE, LocalDateTime.now(clock));
        DeviceGenerationGateView before = catalogRepository.findGenerationGate(normalized).orElse(null);
        if (before == null) {
            return ApiResult.fail(404, "E1_GATE_NOT_FOUND");
        }
        String requestedPhase = request.phase() == null ? before.phase() : request.phase();
        if (!isConfiguredE1PhaseId(requestedPhase)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "E1_GATE_PHASE_INVALID");
        }
        String phase = normalizeE1Phase(requestedPhase);
        boolean eligibility = request.eligibility() == null ? Boolean.TRUE.equals(before.eligibility()) : request.eligibility();
        boolean forceUnlock = request.forceUnlock() == null ? Boolean.TRUE.equals(before.forceUnlock()) : request.forceUnlock();
        ApiResult<Map<String, Object>> forceUnlockGuard = requireE1ForceUnlockTransition(
                Boolean.TRUE.equals(before.forceUnlock()), forceUnlock, eligibility, phase);
        if (forceUnlockGuard != null) {
            return forceUnlockGuard;
        }
        DeviceGenerationGateView updated = catalogRepository.saveGenerationGate(
                normalized,
                StringUtils.hasText(request.name()) ? request.name().trim() : before.name(),
                request.releaseMonth() == null ? before.releaseMonth() : normalizeReleaseMonth(request.releaseMonth()),
                phase,
                before.discount(),
                eligibility,
                request.phaseOffset() == null ? before.phaseOffset() : normalizePhaseOffset(request.phaseOffset()),
                forceUnlock,
                request.status() == null ? before.status() : normalizeGenerationGateStatus(request.status()),
                LocalDateTime.now(clock));
        auditRequired("E1_GENERATION_GATE_UPDATED", "DEVICE_GENERATION_GATE", normalized, request.operator(), detail(
                "generationId", normalized,
                "before", generationGateSnapshot(before),
                "after", generationGateSnapshot(updated),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return e1GenerationGates();
        });
    }

    @Transactional
    public ApiResult<Map<String, Object>> archiveE1GenerationGate(String skuId, String idempotencyKey, DeviceGenerationGateArchiveRequest request) {
        ApiResult<Map<String, Object>> guard = requireE1Command(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        return e1Idempotent("E1_GATE_ARCHIVE", idempotencyKey, skuId, request, () -> {
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("E", "device_generation_gate", skuId) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        String normalized = normalizeGenerationId(skuId);
        DeviceGenerationGateView before = catalogRepository.findGenerationGate(normalized).orElse(null);
        if (before == null) {
            return ApiResult.fail(404, "E1_GATE_NOT_FOUND");
        }
        catalogRepository.archiveGenerationGate(normalized, LocalDateTime.now(clock));
        auditRequired("E1_GENERATION_GATE_ARCHIVED", "DEVICE_GENERATION_GATE", normalized, request.operator(), detail(
                "generationId", normalized,
                "name", before.name(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return e1GenerationGates();
        });
    }

    public ApiResult<Map<String, Object>> updateE1GenerationGate(String idempotencyKey, E3ConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireE1Command(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        return e1Idempotent("E1_GATE_FIELD_UPDATE", idempotencyKey, request.key(), request, () -> {
        catalogRepository.backfillPhaseReferences(E1_PHASE_SCOPE, LocalDateTime.now(clock));
        String[] key;
        String value;
        try {
            key = normalizeE1GateKey(request.key());
            value = normalizeE1GateValue(key[1], request.value());
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage());
        }
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("E", "device_generation_gate", key[0]) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        DeviceGenerationGateView before = catalogRepository.findGenerationGate(key[0]).orElse(null);
        if (before == null) {
            return ApiResult.fail(404, "E1_GATE_NOT_FOUND");
        }
        String requestedPhase = "phase".equals(key[1]) ? value : before.phase();
        if (!isConfiguredE1PhaseId(requestedPhase)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "E1_GATE_PHASE_INVALID");
        }
        String nextPhase = normalizeE1Phase(requestedPhase);
        boolean nextEligibility = "eligibility".equals(key[1])
                ? Boolean.parseBoolean(value)
                : Boolean.TRUE.equals(before.eligibility());
        boolean nextForceUnlock = "forceUnlock".equals(key[1])
                ? Boolean.parseBoolean(value)
                : Boolean.TRUE.equals(before.forceUnlock());
        ApiResult<Map<String, Object>> forceUnlockGuard = requireE1ForceUnlockTransition(
                Boolean.TRUE.equals(before.forceUnlock()), nextForceUnlock, nextEligibility, nextPhase);
        if (forceUnlockGuard != null) {
            return forceUnlockGuard;
        }
        String oldValue = generationGateFieldValue(before, key[1]);
        DeviceGenerationGateView updated = saveGenerationGateField(before, key[1], value);
        auditRequired("E1_GENERATION_GATE_CHANGED", "DEVICE_GENERATION_GATE", key[0], request.operator(), detail(
                "generationId", key[0],
                "field", key[1],
                "oldValue", oldValue,
                "newValue", generationGateFieldValue(updated, key[1]),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return e1GenerationGates();
        });
    }

    @Transactional
    public ApiResult<Map<String, Object>> updateE1EarlyAccess(
            String idempotencyKey, DeviceEarlyAccessUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireE1Command(
                idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (request.enabled() == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "E1_EARLY_ACCESS_ENABLED_REQUIRED");
        }
        if (request.leadDays() == null || !Set.of(7, 14, 30, 60, 90).contains(request.leadDays())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "E1_EARLY_ACCESS_LEAD_DAYS_INVALID");
        }
        return e1Idempotent("E1_EARLY_ACCESS_UPDATE", idempotencyKey, "trade-in", request, () -> {
            if (!A2ReplayContext.isReplaying()
                    && lockMapper.countActiveByTarget("E", "device_release_early_access", "trade-in") > 0) {
                return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
            }
            Map<String, String> before = new LinkedHashMap<>(deviceRepository.e3Config());
            String actor = operator(request.operator());
            deviceRepository.upsertE3Config(
                    "earlyAccessEnabled", String.valueOf(request.enabled()), "BOOLEAN", actor);
            deviceRepository.upsertE3Config(
                    "earlyAccessLeadDays", String.valueOf(request.leadDays()), "INTEGER", actor);
            auditRequired("E1_EARLY_ACCESS_CHANGED", "DEVICE_RELEASE_EARLY_ACCESS", "trade-in", request.operator(), detail(
                    "beforeEnabled", before.get("earlyAccessEnabled"),
                    "beforeLeadDays", before.get("earlyAccessLeadDays"),
                    "enabled", request.enabled(),
                    "leadDays", request.leadDays(),
                    "reason", request.reason().trim(),
                    "idempotencyKey", idempotencyKey.trim()));
            return ApiResult.ok(detail(
                    "domain", "E1",
                    "targetId", "trade-in",
                    "enabled", request.enabled(),
                    "leadDays", request.leadDays()));
        });
    }

    public ApiResult<Map<String, Object>> e3Overview() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "E3");
        response.put("config", deviceRepository.e3Config());
        response.put("restoreEndpoint", "POST /api/admin/devices/{id}/restore");
        response.put("sources", List.of("nx_compute_e3_config", "nx_tradein_application", "nx_trade_in_order", "nx_user_device"));
        return ApiResult.ok(response);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> updateE3Config(String idempotencyKey, E3ConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String key;
        try {
            key = normalizeE3Key(request.key());
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage());
        }
        String trustedOperator = operator(request.operator());
        E3ConfigUpdateRequest trusted = new E3ConfigUpdateRequest(
                key, request.value(), request.reason().trim(), trustedOperator);
        return deviceIdempotent("E3_CONFIG_UPDATE", idempotencyKey, key, trusted, () -> {
            if (!A2ReplayContext.isReplaying()
                    && lockMapper.countActiveByTarget("E", "device_e3_config", key) > 0) {
                return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
            }
            Map<String, String> before = deviceRepository.e3Config();
            E3ConfigValue value;
            try {
                value = normalizeE3Value(key, trusted.value());
                validateE3Invariants(before, key, value.value());
            } catch (IllegalArgumentException ex) {
                return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage());
            }
            if (value.value().equals(before.get(key))) {
                return ApiResult.fail(409, "E3_VALUE_UNCHANGED");
            }
            Map<String, String> candidate = new LinkedHashMap<>(before);
            candidate.put(key, value.value());
            if (e3AmplifiesFinancialOutflow(before, candidate, Set.of(key)) && coverageBelowRedline()) {
                return ApiResult.fail(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus(),
                        OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
            }
            deviceRepository.upsertE3Config(key, value.value(), value.valueType(), trustedOperator);
            Map<String, String> oldValues = new LinkedHashMap<>();
            oldValues.put(key, before.get(key));
            Map<String, String> newValues = new LinkedHashMap<>();
            newValues.put(key, value.value());
            recordE3ConfigChange(
                    key,
                    oldValues,
                    newValues,
                    trustedOperator,
                    trusted.reason(),
                    idempotencyKey,
                    A2ReplayContext.isReplaying() ? "a2" : "direct");
            return e3Overview();
        });
    }

    private ApiResult<Map<String, Object>> updateE3ConfigBatch(
            String idempotencyKey,
            Map<String, Object> rawValues,
            String reason,
            String operator) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, reason);
        if (guard != null) {
            return guard;
        }
        if (rawValues == null || rawValues.isEmpty()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "E3_CONFIG_VALUES_REQUIRED");
        }
        Map<String, String> before = new LinkedHashMap<>(deviceRepository.e3Config());
        Map<String, E3ConfigValue> normalized = new LinkedHashMap<>();
        Map<String, String> candidate = new LinkedHashMap<>(before);
        try {
            for (Map.Entry<String, Object> entry : rawValues.entrySet()) {
                String key = normalizeE3Key(entry.getKey());
                E3ConfigValue value = normalizeE3Value(key, entry.getValue() == null ? null : String.valueOf(entry.getValue()));
                normalized.put(key, value);
                candidate.put(key, value.value());
            }
            validateE3Candidate(candidate);
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage());
        }
        Map<String, E3ConfigValue> changed = new LinkedHashMap<>();
        normalized.forEach((key, value) -> {
            if (!value.value().equals(before.get(key))) {
                changed.put(key, value);
            }
        });
        if (changed.isEmpty()) {
            return ApiResult.fail(409, "E3_VALUE_UNCHANGED");
        }
        if (e3AmplifiesFinancialOutflow(before, candidate, changed.keySet()) && coverageBelowRedline()) {
            return ApiResult.fail(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus(),
                    OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
        }
        String actor = operator(operator);
        changed.forEach((key, value) -> deviceRepository.upsertE3Config(key, value.value(), value.valueType(), actor));
        Map<String, String> oldValues = new LinkedHashMap<>();
        Map<String, String> newValues = new LinkedHashMap<>();
        changed.forEach((key, value) -> {
            oldValues.put(key, before.get(key));
            newValues.put(key, value.value());
        });
        recordE3ConfigChange(
                String.join(",", changed.keySet()), oldValues, newValues, actor, reason.trim(), idempotencyKey, "a2");
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
        String trustedOperator = operator(request.operator());
        DeviceTradeinActionRequest trusted = new DeviceTradeinActionRequest(
                request.deviceId(), request.reason().trim(), trustedOperator);
        return deviceIdempotent(
                "E3_TRADEIN_" + normalizedOperation.toUpperCase(Locale.ROOT),
                idempotencyKey,
                String.valueOf(request.deviceId()),
                trusted,
                () -> {
                    if (!A2ReplayContext.isReplaying()
                            && lockMapper.countActiveByTarget("E", "device", String.valueOf(trusted.deviceId())) > 0) {
                        return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
                    }
                    DeviceOpsView before = deviceRepository.findDevice(trusted.deviceId()).orElse(null);
                    if (before == null) {
                        return ApiResult.fail(404, "DEVICE_NOT_FOUND");
                    }
                    LocalDateTime now = LocalDateTime.now(clock);
                    String tradeInNo = "TI-" + now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                            + "-" + trusted.deviceId();
                    DeviceOpsView updated = deviceRepository
                            .executeTradeinAction(normalizedOperation, trusted.deviceId(), tradeInNo, now)
                            .orElse(null);
                    if (updated == null) {
                        return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(),
                                OpsErrorCode.INVALID_STATE_TRANSITION.name());
                    }
                    Map<String, Object> event = detail(
                            "operation", normalizedOperation,
                            "device_id", trusted.deviceId(),
                            "instance_no", updated.instanceNo(),
                            "before_status", before.status(),
                            "after_status", updated.status(),
                            "trade_in_no", "replace".equals(normalizedOperation) ? tradeInNo : "",
                            "operator", trustedOperator,
                            "reason", trusted.reason(),
                            "ts", clock.millis());
                    outboxService.publish("E3_TRADEIN", String.valueOf(trusted.deviceId()),
                            "admin.tradein_action_executed", event);
                    auditRequired("admin.tradein_action_executed", "DEVICE", String.valueOf(trusted.deviceId()),
                            trustedOperator, detail(
                                    "operation", normalizedOperation,
                                    "deviceId", trusted.deviceId(),
                                    "instanceNo", updated.instanceNo(),
                                    "fromStatus", before.status(),
                                    "toStatus", updated.status(),
                                    "tradeInNo", "replace".equals(normalizedOperation) ? tradeInNo : null,
                                    "reason", trusted.reason(),
                                    "idempotencyKey", idempotencyKey.trim()));
                    return ApiResult.ok(updated);
                });
    }

    public ApiResult<DeviceOpsView> restoreDevice(Long deviceId, String idempotencyKey, DeviceRestoreRequest request) {
        ApiResult<DeviceOpsView> guard = requireDeviceCommand(deviceId, idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("E", "device", String.valueOf(deviceId)) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
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

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<DeviceDatacenterView> createDatacenter(String idempotencyKey, DeviceDatacenterUpsertRequest request) {
        ApiResult<DeviceDatacenterView> guard = requireDatacenterCommand(idempotencyKey, request, true);
        if (guard != null) {
            return guard;
        }
        String dc = normalizeDc(request.dcLocation());
        String trustedOperator = operator(request.operator());
        DeviceDatacenterUpsertRequest source = normalizeDatacenterRequest(dc, request);
        DeviceDatacenterUpsertRequest trusted = new DeviceDatacenterUpsertRequest(
                source.dcLocation(), source.regionLabel(), source.status(), source.sortOrder(),
                request.reason().trim(), trustedOperator, source.location(), source.displayName());
        return deviceIdempotent("E5_DATACENTER_CREATE", idempotencyKey, dc, trusted, () -> {
            if (!A2ReplayContext.isReplaying()
                    && lockMapper.countActiveByTarget("E", "device_datacenter", dc) > 0) {
                return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
            }
            if (deviceRepository.findDatacenter(dc).isPresent()) {
                return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DATACENTER_ALREADY_EXISTS");
            }
            DeviceDatacenterView created = deviceRepository.createDatacenter(
                    trusted, trustedOperator, LocalDateTime.now(clock));
            recordE5DatacenterEvent("admin.datacenter_created", created, trustedOperator,
                    trusted.reason(), idempotencyKey, detail("operation", "create"));
            return ApiResult.ok(created);
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<DeviceDatacenterView> updateDatacenter(String dcLocation, String idempotencyKey, DeviceDatacenterUpsertRequest request) {
        ApiResult<DeviceDatacenterView> guard = requireDatacenterCommand(idempotencyKey, request, false);
        if (guard != null) {
            return guard;
        }
        String dc = normalizeDc(dcLocation);
        String nextDc = StringUtils.hasText(request.dcLocation()) ? normalizeDc(request.dcLocation()) : dc;
        String trustedOperator = operator(request.operator());
        DeviceDatacenterUpsertRequest source = normalizeDatacenterRequest(nextDc, request);
        DeviceDatacenterUpsertRequest trusted = new DeviceDatacenterUpsertRequest(
                source.dcLocation(), source.regionLabel(), source.status(), source.sortOrder(),
                request.reason().trim(), trustedOperator, source.location(), source.displayName());
        return deviceIdempotent("E5_DATACENTER_UPDATE", idempotencyKey, dc, trusted, () -> {
            if (!A2ReplayContext.isReplaying()
                    && lockMapper.countActiveByTarget("E", "device_datacenter", dc) > 0) {
                return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
            }
            DeviceDatacenterView before = deviceRepository.findDatacenter(dc).orElse(null);
            if (before == null) {
                return ApiResult.fail(404, "DATACENTER_NOT_FOUND");
            }
            if (!dc.equals(nextDc) && deviceRepository.findDatacenter(nextDc).isPresent()) {
                return ApiResult.fail(409, "DATACENTER_ID_ALREADY_EXISTS");
            }
            DeviceDatacenterView updated = deviceRepository
                    .updateDatacenter(dc, trusted, trustedOperator, LocalDateTime.now(clock)).orElse(before);
            recordE5DatacenterEvent("admin.datacenter_updated", updated, trustedOperator,
                    trusted.reason(), idempotencyKey, detail(
                            "beforeDcLocation", dc,
                            "beforeDisplayName", before.displayName(),
                            "beforeLocation", before.location(),
                            "beforeStatus", before.status(),
                            "afterStatus", updated.status(),
                            "operation", "update"));
            return ApiResult.ok(updated);
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> deleteDatacenter(String dcLocation, String idempotencyKey, DatacenterOpsRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        ApiResult<Map<String, Object>> reasonGuard = requireE5Command(idempotencyKey, request.reason());
        if (reasonGuard != null) {
            return reasonGuard;
        }
        String dc = normalizeDc(dcLocation);
        String trustedOperator = operator(request.operator());
        DatacenterOpsRequest trusted = new DatacenterOpsRequest(request.reason().trim(), trustedOperator);
        return deviceIdempotent("E5_DATACENTER_DELETE", idempotencyKey, dc, trusted, () -> {
            if (!A2ReplayContext.isReplaying()
                    && lockMapper.countActiveByTarget("E", "device_datacenter", dc) > 0) {
                return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
            }
            DeviceDatacenterView before = deviceRepository.findDatacenter(dc).orElse(null);
            if (before == null) {
                return ApiResult.fail(404, "DATACENTER_NOT_FOUND");
            }
            // 跨域硬保护:仍有设备/待履约订单/SKU 引用时拒绝删除,并回具体计数。
            DatacenterReferenceCount refs = deviceRepository.countDatacenterReferences(dc);
            if (refs.hasAny()) {
                return ApiResult.fail(409, "DATACENTER_HAS_REFERENCES", detail(
                        "dcLocation", dc,
                        "deviceCount", refs.devices(),
                        "pendingOrderCount", refs.pendingOrders(),
                        "skuCount", refs.skus()));
            }
            if (!deviceRepository.softDeleteDatacenter(dc, trustedOperator, LocalDateTime.now(clock))) {
                return ApiResult.fail(409, "DATACENTER_DELETE_CONFLICT");
            }
            // 同步设备表 dc_location(置空防悬挂/笛儿)与运营状态行(软删)。
            deviceRepository.syncDatacenterReferencesOnDelete(dc, trustedOperator, LocalDateTime.now(clock));
            recordE5DatacenterEvent("admin.datacenter_deleted", before, trustedOperator,
                    trusted.reason(), idempotencyKey, detail("operation", "delete"));
            return ApiResult.ok(detail("dcLocation", dc, "deleted", true));
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> pauseDatacenter(String dcLocation, String idempotencyKey, DatacenterOpsRequest request) {
        ApiResult<Map<String, Object>> guard = requireE5Command(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String dc = normalizeDc(dcLocation);
        String trustedOperator = operator(request.operator());
        DatacenterOpsRequest trusted = new DatacenterOpsRequest(request.reason().trim(), trustedOperator);
        return deviceIdempotent("E5_DATACENTER_PAUSE", idempotencyKey, dc, trusted, () -> {
            if (!A2ReplayContext.isReplaying()
                    && lockMapper.countActiveByTarget("E", "device_datacenter", dc) > 0) {
                return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
            }
            DeviceDatacenterView before = deviceRepository.findDatacenter(dc).orElse(null);
            if (before == null) {
                return ApiResult.fail(404, "DATACENTER_NOT_FOUND");
            }
            deviceRepository.pauseDatacenter(dc, trusted.reason(), trustedOperator, LocalDateTime.now(clock));
            recordE5DatacenterEvent("admin.datacenter_updated", before, trustedOperator,
                    trusted.reason(), idempotencyKey, detail("operation", "pause"));
            return overview();
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> resumeDatacenter(String dcLocation, String idempotencyKey, DatacenterOpsRequest request) {
        ApiResult<Map<String, Object>> guard = requireE5Command(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String dc = normalizeDc(dcLocation);
        String trustedOperator = operator(request.operator());
        DatacenterOpsRequest trusted = new DatacenterOpsRequest(request.reason().trim(), trustedOperator);
        return deviceIdempotent("E5_DATACENTER_RESUME", idempotencyKey, dc, trusted, () -> {
            if (!A2ReplayContext.isReplaying()
                    && lockMapper.countActiveByTarget("E", "device_datacenter", dc) > 0) {
                return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
            }
            DeviceDatacenterView before = deviceRepository.findDatacenter(dc).orElse(null);
            if (before == null) {
                return ApiResult.fail(404, "DATACENTER_NOT_FOUND");
            }
            deviceRepository.resumeDatacenter(dc, trustedOperator, LocalDateTime.now(clock));
            recordE5DatacenterEvent("admin.datacenter_updated", before, trustedOperator,
                    trusted.reason(), idempotencyKey, detail("operation", "resume"));
            return overview();
        });
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

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<DeviceOpsView> activateE5Device(
            Long deviceId, boolean force, String idempotencyKey, DeviceE5ActionRequest request) {
        ApiResult<DeviceOpsView> guard = requireE5DeviceCommand(deviceId, idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        if (force && !A2ReplayContext.isReplaying()) {
            return ApiResult.fail(409, "A2_CONFIRMATION_REQUIRED");
        }
        String trustedOperator = operator(request.operator());
        DeviceE5ActionRequest trusted = new DeviceE5ActionRequest(request.reason().trim(), trustedOperator);
        String scope = force ? "E5_DEVICE_FORCE_ACTIVATE" : "E5_DEVICE_ACTIVATE";
        return deviceIdempotent(scope, idempotencyKey, String.valueOf(deviceId), trusted, () -> {
            if (!A2ReplayContext.isReplaying()
                    && lockMapper.countActiveByTarget("E", "device", String.valueOf(deviceId)) > 0) {
                return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
            }
            DeviceOpsView before = deviceRepository.findDevice(deviceId).orElse(null);
            if (before == null) {
                return ApiResult.fail(404, "DEVICE_NOT_FOUND");
            }
            String status = normalizeExact(before.status()).toUpperCase(Locale.ROOT);
            Set<String> allowed = force
                    ? Set.of("INVENTORY", "DEACTIVATED", "RECYCLED", "INACTIVE", "RETIRED", "UNBOUND")
                    : Set.of("INVENTORY");
            if (!allowed.contains(status)) {
                return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(),
                        OpsErrorCode.INVALID_STATE_TRANSITION.name());
            }
            if (before.userId() == null || before.userId() <= 0) {
                return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DEVICE_USER_REQUIRED");
            }
            if (deviceRepository.countActiveDevicesByUser(before.userId()) >= E5_MAX_DEVICES_PER_USER) {
                return ApiResult.fail(409, "MAX_DEVICES_PER_USER_EXCEEDED");
            }
            DeviceOpsView updated = deviceRepository.activateDevice(deviceId, LocalDateTime.now(clock)).orElse(null);
            if (updated == null) {
                return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(),
                        OpsErrorCode.INVALID_STATE_TRANSITION.name());
            }
            recordE5DeviceEvent("admin.device_activated", updated, before.status(), updated.status(),
                    force ? "force" : "manual", trustedOperator, trusted.reason(), idempotencyKey);
            return ApiResult.ok(updated);
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<DeviceOpsView> deactivateE5Device(
            Long deviceId, boolean unbind, String idempotencyKey, DeviceE5ActionRequest request) {
        ApiResult<DeviceOpsView> guard = requireE5DeviceCommand(deviceId, idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        if (unbind && !A2ReplayContext.isReplaying()) {
            return ApiResult.fail(409, "A2_CONFIRMATION_REQUIRED");
        }
        String trustedOperator = operator(request.operator());
        DeviceE5ActionRequest trusted = new DeviceE5ActionRequest(request.reason().trim(), trustedOperator);
        String scope = unbind ? "E5_DEVICE_UNBIND" : "E5_DEVICE_DEACTIVATE";
        return deviceIdempotent(scope, idempotencyKey, String.valueOf(deviceId), trusted, () -> {
            if (!A2ReplayContext.isReplaying()
                    && lockMapper.countActiveByTarget("E", "device", String.valueOf(deviceId)) > 0) {
                return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
            }
            DeviceOpsView before = deviceRepository.findDevice(deviceId).orElse(null);
            if (before == null) {
                return ApiResult.fail(404, "DEVICE_NOT_FOUND");
            }
            DeviceOpsView updated = deviceRepository
                    .deactivateE5Device(deviceId, unbind, LocalDateTime.now(clock)).orElse(null);
            if (updated == null) {
                return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(),
                        OpsErrorCode.INVALID_STATE_TRANSITION.name());
            }
            recordE5DeviceEvent("admin.device_deactivated",
                    updated, before.status(), updated.status(), unbind ? "unbind" : "manual",
                    trustedOperator, trusted.reason(), idempotencyKey);
            return ApiResult.ok(updated);
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> batchE5Devices(
            boolean pause, String idempotencyKey, DeviceE5BatchRequest request) {
        ApiResult<Map<String, Object>> guard = requireE5BatchCommand(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        String trustedOperator = operator(request.operator());
        DeviceE5BatchRequest trusted = new DeviceE5BatchRequest(
                request.userId(), request.reason().trim(), trustedOperator);
        String scope = pause ? "E5_DEVICE_BATCH_PAUSE" : "E5_DEVICE_BATCH_RESUME";
        return deviceIdempotent(scope, idempotencyKey, String.valueOf(request.userId()), trusted, () -> {
            List<Long> deviceIds = deviceRepository.lockE5BatchCandidateIds(trusted.userId(), pause, 200).stream()
                    .filter(Objects::nonNull)
                    .toList();
            if (deviceIds.isEmpty()) {
                return ApiResult.fail(404, pause ? "ACTIVE_USER_DEVICES_NOT_FOUND" : "PAUSED_USER_DEVICES_NOT_FOUND");
            }
            int affectedRows = pause
                    ? deviceRepository.pauseDevicesByUser(trusted.userId(), trusted.reason(), LocalDateTime.now(clock))
                    : deviceRepository.resumeDevicesByUser(trusted.userId(), LocalDateTime.now(clock));
            if (affectedRows <= 0) {
                throw new BizException(409, "E5_BATCH_STATE_CHANGED");
            }
            int changed = deviceIds.size();
            String eventType = pause ? "admin.device_paused" : "admin.device_resumed";
            Map<String, Object> payload = detail(
                    "user_id", trusted.userId(), "device_ids", deviceIds, "scope", "batch",
                    "changed_count", changed,
                    "operator", trustedOperator, "reason", trusted.reason(), "ts", clock.millis());
            outboxService.publish("E5_DEVICE_BATCH", String.valueOf(trusted.userId()), eventType, payload);
            auditRequired(eventType, "USER_DEVICE_BATCH", String.valueOf(trusted.userId()), trustedOperator,
                    detail("userId", trusted.userId(), "changedCount", changed,
                            "reason", trusted.reason(), "idempotencyKey", idempotencyKey.trim()));
            return ApiResult.ok(detail("userId", trusted.userId(), "changedCount", changed,
                    "paused", pause));
        });
    }

    private void recordE5DeviceEvent(
            String eventType, DeviceOpsView device, String beforeStatus, String afterStatus,
            String mode, String trustedOperator, String reason, String idempotencyKey) {
        Map<String, Object> payload = detail(
                "device_id", device.id(), "user_id", device.userId(), "instance_no", device.instanceNo(),
                "before_status", beforeStatus, "after_status", afterStatus, "mode", mode,
                "operator", trustedOperator, "reason", reason, "ts", clock.millis());
        outboxService.publish("E5_DEVICE", String.valueOf(device.id()), eventType, payload);
        auditRequired(eventType, "DEVICE", String.valueOf(device.id()), trustedOperator,
                detail("deviceId", device.id(), "userId", device.userId(), "instanceNo", device.instanceNo(),
                        "beforeStatus", beforeStatus, "afterStatus", afterStatus, "mode", mode,
                        "reason", reason, "idempotencyKey", idempotencyKey.trim()));
    }

    private void recordE5DatacenterEvent(
            String eventType, DeviceDatacenterView datacenter, String trustedOperator,
            String reason, String idempotencyKey, Map<String, Object> extraDetail) {
        Map<String, Object> payload = detail(
                "id", datacenter.dcLocation(), "display_name", datacenter.displayName(),
                "operator", trustedOperator, "reason", reason, "ts", clock.millis());
        outboxService.publish("E5_DATACENTER", datacenter.dcLocation(), eventType, payload);
        Map<String, Object> auditDetail = detail(
                "dcLocation", datacenter.dcLocation(), "location", datacenter.location(),
                "displayName", datacenter.displayName(), "reason", reason,
                "idempotencyKey", idempotencyKey.trim());
        if (extraDetail != null) {
            auditDetail.putAll(extraDetail);
        }
        auditRequired(eventType, "DEVICE_DATACENTER", datacenter.dcLocation(), trustedOperator, auditDetail);
    }

    private ApiResult<DeviceOpsView> requireE5DeviceCommand(
            Long deviceId, String idempotencyKey, DeviceE5ActionRequest request) {
        ApiResult<Map<String, Object>> command = requireE5Command(
                idempotencyKey, request == null ? null : request.reason());
        if (command != null) {
            return ApiResult.fail(command.getCode(), command.getMessage());
        }
        if (deviceId == null || deviceId <= 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DEVICE_ID_REQUIRED");
        }
        return null;
    }

    private ApiResult<Map<String, Object>> requireE5BatchCommand(
            String idempotencyKey, DeviceE5BatchRequest request) {
        ApiResult<Map<String, Object>> command = requireE5Command(
                idempotencyKey, request == null ? null : request.reason());
        if (command != null) {
            return command;
        }
        if (request.userId() == null || request.userId() <= 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "USER_ID_REQUIRED");
        }
        return null;
    }

    private ApiResult<Map<String, Object>> requireE5Command(String idempotencyKey, String reason) {
        ApiResult<Map<String, Object>> command = requireCommand(idempotencyKey, reason);
        if (command != null) {
            return command;
        }
        int length = reason.trim().codePointCount(0, reason.trim().length());
        if (length < 8 || length > 200) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "REASON_LENGTH_INVALID");
        }
        return null;
    }

    private ApiResult<Map<String, Object>> requireE1Command(String idempotencyKey, String reason) {
        ApiResult<Map<String, Object>> command = requireCommand(idempotencyKey, reason);
        if (command != null) {
            return command;
        }
        String normalized = reason.trim();
        int length = normalized.codePointCount(0, normalized.length());
        if (length < 8 || length > 200) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "REASON_LENGTH_INVALID");
        }
        return null;
    }

    private ApiResult<DeviceDatacenterView> requireDatacenterCommand(String idempotencyKey, DeviceDatacenterUpsertRequest request, boolean create) {
        ApiResult<Map<String, Object>> command = requireE5Command(idempotencyKey, request == null ? null : request.reason());
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
        if (!StringUtils.hasText(request.location())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DATACENTER_LOCATION_NAME_REQUIRED");
        }
        if (!StringUtils.hasText(request.displayName())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DATACENTER_DISPLAY_NAME_REQUIRED");
        }
        if (normalizeDatacenterStatus(request.status()) == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DATACENTER_STATUS_INVALID");
        }
        return null;
    }

    private ApiResult<DeviceSkuView> requireSkuCommand(String idempotencyKey, DeviceSkuUpsertRequest request) {
        ApiResult<Map<String, Object>> command = requireE1Command(idempotencyKey, request == null ? null : request.reason());
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
        if (request.generation() != null && (request.generation() < 1 || request.generation() > 3)) {
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
                request.generation() == null ? 1 : request.generation(),
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
                operator(request.operator()));
    }

    private ApiResult<DeviceReviewView> requireReviewCommand(String idempotencyKey, DeviceReviewUpsertRequest request) {
        ApiResult<Map<String, Object>> command = requireE1Command(idempotencyKey, request == null ? null : request.reason());
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
        ApiResult<Map<String, Object>> command = requireE1Command(idempotencyKey, request == null ? null : request.reason());
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
                || request.minReward().compareTo(BigDecimal.ZERO) < 0
                || request.maxReward().compareTo(request.minReward()) < 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TASK_REWARD_RANGE_INVALID");
        }
        if (parseVram(request.minVram()) < 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TASK_MIN_VRAM_INVALID");
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
        String normalizedOrderNo = normalizeId(orderNo);
        DeviceOrderView before = catalogRepository.findOrder(normalizedOrderNo).orElse(null);
        DeviceOrderFacts facts = catalogRepository.findOrderFacts(normalizedOrderNo).orElse(null);
        if (before == null || facts == null) {
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
        if ("refunded".equals(toState)) {
            TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
            if (refundCoverageBlocked(coverage, facts.amountUsdt())) {
                return ApiResult.fail(
                        OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus(),
                        OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
            }
            String channel = StringUtils.hasText(request.refundChannel())
                    ? request.refundChannel().trim().toUpperCase(Locale.ROOT)
                    : "WALLET";
            if (!StringUtils.hasText(facts.paymentNo()) && !"WALLET".equals(channel)) {
                return ApiResult.fail(409, "ORDER_REFUND_CHANNEL_UNAVAILABLE");
            }
        }

        LocalDateTime now = LocalDateTime.now(clock);
        DeviceOrderView updated = catalogRepository.updateOrderState(normalizedOrderNo, fromState, toState, now).orElse(null);
        if (updated == null) {
            return ApiResult.fail(409, "ORDER_STATE_CONFLICT");
        }
        E4OrderRefundSettlementFacade.Settlement settlement = null;
        if ("E4_ORDER_REFUNDED".equals(auditAction)) {
            settlement = refundSettlementFacade.settle(
                    normalizedOrderNo,
                    facts.userId(),
                    facts.amountUsdt(),
                    StringUtils.hasText(request.refundChannel()) ? request.refundChannel() : "WALLET",
                    request.reason().trim(),
                    request.operator(),
                    idempotencyKey.trim());
            catalogRepository.rollbackOrderAssets(normalizedOrderNo, now);
        }
        catalogRepository.recordOrderHistory(normalizedOrderNo, fromState, toState, request.reason().trim(),
                request.operator(), idempotencyKey.trim(), now);
        auditRequired(auditAction, "DEVICE_ORDER", normalizedOrderNo, request.operator(), detail(
                "orderNo", normalizedOrderNo,
                "fromState", fromState,
                "toState", toState,
                "amount", before.amount(),
                "refundChannel", settlement == null ? null : settlement.channel(),
                "walletBefore", settlement == null ? null : settlement.walletBefore(),
                "walletAfter", settlement == null ? null : settlement.walletAfter(),
                "cumulativeDepositBefore", settlement == null ? null : settlement.cumulativeDepositBefore(),
                "cumulativeDepositAfter", settlement == null ? null : settlement.cumulativeDepositAfter(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        if (settlement != null) {
            publishRefundEvents(normalizedOrderNo, facts, before, updated, request, settlement);
        } else {
            publishOrderEvent(auditAction, normalizedOrderNo, before, updated, request.operator());
        }
        return ApiResult.ok(updated);
    }

    private boolean canMoveOrder(String fromState, String toState) {
        return ORDER_TRANSITIONS.getOrDefault(fromState, Set.of()).contains(toState);
    }

    private boolean canMoveOrderMainState(String fromState, String toState) {
        return ORDER_MAIN_STATES.contains(toState)
                && ORDER_TRANSITIONS.getOrDefault(fromState, Set.of()).contains(toState);
    }

    private void publishOrderEvent(
            String eventType,
            String orderNo,
            DeviceOrderView before,
            DeviceOrderView after,
            String operator) {
        outboxService.publish("E4_ORDER", orderNo, eventType, detail(
                "orderNo", orderNo,
                "userNo", before.userNo(),
                "fromState", before.state(),
                "toState", after.state(),
                "amount", before.amount(),
                "operator", operator,
                "ts", clock.millis()));
    }

    private void publishRefundEvents(
            String orderNo,
            DeviceOrderFacts facts,
            DeviceOrderView before,
            DeviceOrderView after,
            DeviceOrderActionRequest request,
            E4OrderRefundSettlementFacade.Settlement settlement) {
        BigDecimal cumulativeAdjusted = settlement.cumulativeDepositBefore()
                .subtract(settlement.cumulativeDepositAfter())
                .max(BigDecimal.ZERO);
        Map<String, Object> payload = detail(
                "orderId", orderNo,
                "userId", facts.userId(),
                "amount", facts.amountUsdt(),
                "refundChannel", settlement.channel(),
                "cumulativeDepositAdjusted", cumulativeAdjusted,
                "operator", request.operator(),
                "reason", request.reason().trim(),
                "fromState", before.state(),
                "toState", after.state(),
                "walletBefore", settlement.walletBefore(),
                "walletAfter", settlement.walletAfter(),
                "ledgerBizNo", settlement.ledgerBizNo(),
                "billNo", settlement.billNo());
        outboxService.publish("E4_ORDER", orderNo, "order.refunded", payload);
        outboxService.publish("E4_ORDER", orderNo, "admin.order_refunded", payload);
    }

    private BigDecimal projectedCoverage(TreasuryCoverageSnapshot coverage, BigDecimal refundAmount) {
        if (coverage == null || !coverage.reliable() || coverage.coverageRatio() == null) {
            return null;
        }
        BigDecimal liabilities = coverage.liabilitiesUsd();
        BigDecimal reserve = coverage.reserveUsd();
        BigDecimal amount = refundAmount == null ? BigDecimal.ZERO : refundAmount.max(BigDecimal.ZERO);
        if (liabilities == null || reserve == null || liabilities.signum() <= 0) {
            return coverage.coverageRatio();
        }
        return reserve.multiply(new BigDecimal("100"))
                .divide(liabilities.add(amount), 4, RoundingMode.HALF_UP);
    }

    private boolean refundCoverageBlocked(TreasuryCoverageSnapshot coverage, BigDecimal refundAmount) {
        if (coverage == null || !coverage.reliable() || coverage.coverageRatio() == null
                || coverage.redlinePct() == null) {
            return true;
        }
        BigDecimal projected = projectedCoverage(coverage, refundAmount);
        return coverage.coverageRatio().compareTo(coverage.redlinePct()) < 0
                || projected == null
                || projected.compareTo(coverage.redlinePct()) < 0;
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
        return phaseForMonth(platformMonth, phases);
    }

    private ApiResult<Map<String, Object>> requireE1PhaseArchivable(String phaseId) {
        if (catalogRepository.listPhases(E1_PHASE_SCOPE, false).size() <= 1) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "E1_PHASE_MIN_ONE_REQUIRED");
        }
        if (phaseId.equals(currentE1PhaseId(currentPlatformMonth(), e1PhaseDefs()))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "E1_PHASE_IS_CURRENT");
        }
        if (catalogRepository.countSkusByUnlockPhase(phaseId) > 0
                || catalogRepository.countGenerationGatesByPhase(phaseId) > 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "E1_PHASE_IN_USE");
        }
        return null;
    }

    private DeviceTaskUpsertRequest trustedTaskRequest(DeviceTaskUpsertRequest request) {
        return new DeviceTaskUpsertRequest(
                request.name().trim(), request.price(), request.unit().trim(), request.requirement().trim(),
                request.saturation(), normalizeTaskStatus(request.status()), normalizeUpper(request.taskClass()),
                request.model().trim(), request.minReward(), request.maxReward(), parseVram(request.minVram()) + "GB",
                request.killInit().trim(), request.reason().trim(), operator(request.operator()));
    }

    private ApiResult<Map<String, Object>> requireE1ForceUnlockTransition(
            boolean beforeForceUnlock, boolean forceUnlock, boolean eligibility, String phaseId) {
        if (forceUnlock && !eligibility) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "E1_FORCE_UNLOCK_ELIGIBILITY_REQUIRED");
        }
        if (forceUnlock && !isE1PhaseReached(phaseId)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "E1_FORCE_UNLOCK_PHASE_NOT_REACHED");
        }
        if (!beforeForceUnlock && forceUnlock && !hasAuthority("device_e1_generation_gate_force_unlock")) {
            return ApiResult.fail(403, "E1_FORCE_UNLOCK_FORBIDDEN");
        }
        if (beforeForceUnlock && !forceUnlock && !hasAuthority("device_e1_generation_gate_force_lock")) {
            return ApiResult.fail(403, "E1_FORCE_LOCK_FORBIDDEN");
        }
        return null;
    }

    private ApiResult<DeviceSkuView> requireE1SkuListingAllowed(String skuId) {
        return requireE1SkuListingAllowed(skuId, null, false);
    }

    private ApiResult<DeviceSkuView> requireE1SkuListingAllowed(String skuId, String nextUnlockPhase) {
        return requireE1SkuListingAllowed(skuId, nextUnlockPhase, true);
    }

    private ApiResult<DeviceSkuView> requireE1SkuListingAllowed(
            String skuId, String nextUnlockPhase, boolean useNextUnlockPhase) {
        DeviceSkuView sku = catalogRepository.findSku(skuId).orElse(null);
        String unlockPhase = useNextUnlockPhase
                ? nextUnlockPhase
                : (sku == null ? null : sku.unlockPhase());
        DeviceGenerationGateView gate = catalogRepository.findGenerationGate(skuId).orElse(null);
        if (gate == null || !"active".equals(gate.status())) {
            if (!StringUtils.hasText(unlockPhase)) {
                return null;
            }
            String configuredUnlockPhase = matchConfiguredE1PhaseId(unlockPhase, false);
            if (!StringUtils.hasText(configuredUnlockPhase)) {
                return ApiResult.fail(
                        OpsErrorCode.VALIDATION_FAILED.httpStatus(), "E1_SKU_UNLOCK_PHASE_INVALID");
            }
            if (!isE1PhaseReached(configuredUnlockPhase)) {
                return ApiResult.fail(
                        OpsErrorCode.VALIDATION_FAILED.httpStatus(), "E1_SKU_H1_PHASE_NOT_REACHED");
            }
            return null;
        }
        if (!Boolean.TRUE.equals(gate.eligibility())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "E1_SKU_E5_ELIGIBILITY_REQUIRED");
        }
        String configuredGatePhase = matchConfiguredE1PhaseId(gate.phase(), false);
        if (!StringUtils.hasText(configuredGatePhase) || !isE1PhaseReached(configuredGatePhase)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "E1_SKU_H1_PHASE_NOT_REACHED");
        }
        if (StringUtils.hasText(unlockPhase)) {
            String configuredUnlockPhase = matchConfiguredE1PhaseId(unlockPhase, false);
            if (!StringUtils.hasText(configuredUnlockPhase)) {
                return ApiResult.fail(
                        OpsErrorCode.VALIDATION_FAILED.httpStatus(), "E1_SKU_UNLOCK_PHASE_INVALID");
            }
            if (!configuredGatePhase.equals(configuredUnlockPhase)) {
                return ApiResult.fail(
                        OpsErrorCode.VALIDATION_FAILED.httpStatus(), "E1_SKU_GATE_PHASE_MISMATCH");
            }
        }
        int releaseMonth = gate.releaseMonth() == null ? 0 : gate.releaseMonth();
        int phaseOffset = gate.phaseOffset() == null ? 0 : gate.phaseOffset();
        if (!Boolean.TRUE.equals(gate.forceUnlock()) && currentPlatformMonth() < releaseMonth + phaseOffset) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "E1_SKU_RELEASE_MONTH_NOT_REACHED");
        }
        return null;
    }

    private void publishE1SkuLifecycleEvent(String skuId, String eventType, Map<String, Object> payload) {
        Map<String, Object> trustedPayload = new LinkedHashMap<>(payload);
        Object proposedOperator = trustedPayload.get("operator");
        trustedPayload.put("operator", operator(proposedOperator == null ? null : String.valueOf(proposedOperator)));
        outboxService.publish("DEVICE_SKU", skuId, eventType, trustedPayload);
    }

    private Map<String, Object> e1SkuStatusEvent(
            String skuId, String beforeStatus, String afterStatus, String operator, String reason) {
        return detail(
                "sku_key", skuId,
                "before_status", beforeStatus,
                "after_status", afterStatus,
                "operator", operator(operator),
                "reason", reason.trim());
    }

    private boolean isE1PhaseReached(String phaseId) {
        List<DevicePhaseView> phases = e1PhaseDefs();
        String currentPhaseId = currentE1PhaseId(currentPlatformMonth(), phases);
        int currentIndex = -1;
        int targetIndex = -1;
        for (int i = 0; i < phases.size(); i++) {
            if (phases.get(i).p().equals(currentPhaseId)) {
                currentIndex = i;
            }
            if (phases.get(i).p().equals(phaseId)) {
                targetIndex = i;
            }
        }
        return currentIndex >= 0 && targetIndex >= 0 && targetIndex <= currentIndex;
    }

    private boolean hasAuthority(String authority) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                        .anyMatch(granted -> authority.equals(granted.getAuthority()));
    }

    private boolean readFlag(ComputeConfigRegistry.FlagDef f) {
        return configFacade.activeValue(ComputeConfigRegistry.flagKey(f.key()))
                .map(String::trim)
                .filter(value -> Set.of("on", "off").contains(value))
                .map("on"::equals)
                .orElse(f.defaultOn());
    }

    private String readVal(String key, String fallback) {
        return configFacade.activeValue(key).filter(s -> !s.isBlank()).orElse(fallback);
    }

    private String readComputeVal(String key, String fallback) {
        return configFacade.activeValue(key)
                .map(String::trim)
                .filter(value -> validateComputeValue(key, value) == null)
                .orElse(fallback);
    }

    private List<ComputeConfigView.KeywordView> readKeywords(ComputeConfigRegistry.GpuTierDef t) {
        List<ComputeConfigView.KeywordView> out = new ArrayList<>();
        for (int i = 0; i < ComputeConfigRegistry.KEYWORD_SLOTS.size(); i++) {
            String slot = ComputeConfigRegistry.KEYWORD_SLOTS.get(i);
            String fallback = i < t.keywords().size() ? t.keywords().get(i) : "";
            String key = ComputeConfigRegistry.gpuTierKey(t.id(), slot);
            String v = configFacade.activeValue(key)
                    .map(String::trim)
                    .filter(value -> validateComputeValue(key, value) == null)
                    .orElse(fallback);
            if (!v.isBlank()) {
                out.add(new ComputeConfigView.KeywordView(slot, v));
            }
        }
        return out;
    }

    private BigDecimal coefficientValue(ComputeConfigView compute, String key, String fallback) {
        String value = compute.coefficients().stream()
                .filter(item -> key.equals(item.key()))
                .map(ComputeConfigView.CoeffView::value)
                .findFirst().orElse(fallback);
        return new BigDecimal(value);
    }

    private String computeEventType(String paramKey) {
        if (ComputeConfigRegistry.isFlagParamKey(paramKey)) {
            return "compute.flag_toggled";
        }
        if (ComputeConfigRegistry.isCoefficientParamKey(paramKey)) {
            return "compute.coefficient_changed";
        }
        return "compute.param_changed";
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
            case "eligibility" -> String.valueOf(Boolean.TRUE.equals(gate.eligibility()));
            case "phaseOffset" -> String.valueOf(gate.phaseOffset());
            case "forceUnlock" -> String.valueOf(Boolean.TRUE.equals(gate.forceUnlock()));
            default -> "";
        };
    }

    private DeviceGenerationGateView saveGenerationGateField(DeviceGenerationGateView before, String field, String value) {
        Integer releaseMonth = before.releaseMonth();
        String phase = before.phase();
        Boolean eligibility = before.eligibility();
        Integer phaseOffset = before.phaseOffset();
        Boolean forceUnlock = before.forceUnlock();
        if ("releaseMonth".equals(field)) {
            releaseMonth = normalizeReleaseMonth(Integer.parseInt(value));
        } else if ("phase".equals(field)) {
            phase = normalizeE1Phase(value);
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
                before.discount(),
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
        } else if (normalized.startsWith("E.release.earlyAccess.")) {
            normalized = "earlyAccess." + normalized.substring("E.release.earlyAccess.".length());
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
            case "capacity.band1DeltaPct" -> "capacityBand1DeltaPct";
            case "capacity.band2DeltaPct" -> "capacityBand2DeltaPct";
            case "capacity.band3DeltaPct" -> "capacityBand3DeltaPct";
            case "capacity.floorPct" -> "capacityFloorPct";
            case "capacity.subsidyDays" -> "capacitySubsidyDays";
            case "capacity.applyTo.phone" -> "capacityApplyToPhone";
            case "capacity.applyTo.cloud-share" -> "capacityApplyToCloudShare";
            case "capacity.applyTo.pc-gpu" -> "capacityApplyToPcGpu";
            case "capacity.applyTo.stellarbox-s1" -> "capacityApplyToS1";
            case "capacity.applyTo.stellarbox-pro" -> "capacityApplyToPro";
            case "capacity.applyTo.stellarbox-pro-v2" -> "capacityApplyToProV2";
            case "capacity.applyTo.stellarrack-p1" -> "capacityApplyToRackP1";
            case "capacity.applyTo.stellarrack-p2" -> "capacityApplyToRackP2";
            case "enabled" -> "tradeinEnabled";
            case "ladder.cut1" -> "tradeinLadderCut1";
            case "ladder.cut2" -> "tradeinLadderCut2";
            case "ladder.cut3" -> "tradeinLadderCut3";
            case "ladder.cut4" -> "tradeinLadderCut4";
            case "ladder.credit1" -> "tradeinLadderCredit1";
            case "ladder.credit2" -> "tradeinLadderCredit2";
            case "ladder.credit3" -> "tradeinLadderCredit3";
            case "ladder.credit4" -> "tradeinLadderCredit4";
            case "ladder.credit5" -> "tradeinLadderCredit5";
            case "requireHigherPrice" -> "tradeinRequireHigherPrice";
            case "maxDevicesPerOrder" -> "tradeinMaxDevicesPerOrder";
            case "earlyAccess.enabled" -> "earlyAccessEnabled";
            case "earlyAccess.leadDays" -> "earlyAccessLeadDays";
            default -> normalized;
        };
        if (E3_RETIRED_CONFIG_KEYS.contains(normalized)) {
            throw new IllegalArgumentException("E3_CONFIG_KEY_RETIRED");
        }
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
        if (key.startsWith("capacityApplyTo") || Set.of(
                "tradeinEnabled", "tradeinRequireHigherPrice", "earlyAccessEnabled").contains(key)) {
            String value = normalizeText(raw).toLowerCase(Locale.ROOT);
            if (Set.of("true", "1", "on", "是").contains(value)) {
                return new E3ConfigValue("true", "BOOLEAN");
            }
            if (Set.of("false", "0", "off", "否").contains(value)) {
                return new E3ConfigValue("false", "BOOLEAN");
            }
            throw new IllegalArgumentException("boolean config is invalid");
        }
        BigDecimal value = parseDecimal(raw);
        if (key.startsWith("capacityBand") && key.endsWith("DeltaPct")) {
            if (value.compareTo(BigDecimal.valueOf(-100)) < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new IllegalArgumentException("capacity delta config must be -100-100");
            }
            return numberConfig(value.setScale(2, RoundingMode.HALF_UP));
        }
        if ("capacityFloorPct".equals(key) || key.startsWith("tradeinLadderCut") || key.startsWith("tradeinLadderCredit")) {
            if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new IllegalArgumentException("percent config must be 0-100");
            }
            return numberConfig(value.setScale(2, RoundingMode.HALF_UP));
        }
        if (key.endsWith("Days") || key.endsWith("Seconds") || key.endsWith("Max") || key.endsWith("Months")
                || "stageEarlyEnd".equals(key) || "stageMidEnd".equals(key) || "cycleMonths".equals(key)
                || "inventorySoftMax".equals(key) || "promoMaxPerSession".equals(key) || key.startsWith("taskLock")
                || "tradeinMaxDevicesPerOrder".equals(key)) {
            if (value.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("numeric config must be positive");
            }
            if (value.stripTrailingZeros().scale() > 0) {
                throw new IllegalArgumentException("numeric config must be an integer");
            }
            return numberConfig(new BigDecimal(value.toBigIntegerExact()));
        }
        return numberConfig(value.setScale(4, RoundingMode.HALF_UP));
    }

    private void validateE3Invariants(Map<String, String> current, String changedKey, String changedValue) {
        Map<String, String> candidate = new LinkedHashMap<>(current);
        candidate.put(changedKey, changedValue);
        validateE3Candidate(candidate);
    }

    private void validateE3Candidate(Map<String, String> candidate) {
        if (candidate.keySet().stream().anyMatch(Set.of("stageEarlyEnd", "stageMidEnd", "cycleMonths")::contains)) {
            BigDecimal early = e3Decimal(candidate, "stageEarlyEnd", "3");
            BigDecimal mid = e3Decimal(candidate, "stageMidEnd", "8");
            BigDecimal cycle = e3Decimal(candidate, "cycleMonths", "12");
            if (!(early.compareTo(mid) < 0 && mid.compareTo(cycle) < 0)) {
                throw new IllegalArgumentException("capacity stages must satisfy early < mid < cycle");
            }
        }
        if (candidate.keySet().stream().anyMatch(key -> key.startsWith("tradeinLadderCut"))) {
            List<BigDecimal> cuts = List.of(
                    e3Decimal(candidate, "tradeinLadderCut1", "25"),
                    e3Decimal(candidate, "tradeinLadderCut2", "50"),
                    e3Decimal(candidate, "tradeinLadderCut3", "75"),
                    e3Decimal(candidate, "tradeinLadderCut4", "100"));
            for (int i = 1; i < cuts.size(); i++) {
                if (cuts.get(i - 1).compareTo(cuts.get(i)) >= 0) {
                    throw new IllegalArgumentException("trade-in ladder cuts must be strictly increasing");
                }
            }
        }
        if (candidate.keySet().stream().anyMatch(key -> key.startsWith("tradeinLadderCredit"))) {
            List<BigDecimal> credits = List.of(
                    e3Decimal(candidate, "tradeinLadderCredit1", "75"),
                    e3Decimal(candidate, "tradeinLadderCredit2", "60"),
                    e3Decimal(candidate, "tradeinLadderCredit3", "45"),
                    e3Decimal(candidate, "tradeinLadderCredit4", "30"),
                    e3Decimal(candidate, "tradeinLadderCredit5", "15"));
            for (int i = 1; i < credits.size(); i++) {
                if (credits.get(i - 1).compareTo(credits.get(i)) <= 0) {
                    throw new IllegalArgumentException("trade-in ladder credits must be strictly decreasing");
                }
            }
        }
    }

    private BigDecimal e3Decimal(Map<String, String> config, String key, String fallback) {
        return new BigDecimal(config.getOrDefault(key, fallback));
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
                request.operator(),
                request.location().trim(),
                request.displayName().trim());
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
        String fallback = StringUtils.hasText(operator) ? operator.trim() : "system";
        return AdminActorResolver.resolve(fallback);
    }

    private DeviceTaskView findE2TaskByClass(String taskClass) {
        DeviceTaskView canonical = catalogRepository.findTask(E2_CANONICAL_TASK_ID.get(taskClass)).orElse(null);
        if (canonical != null) {
            return canonical;
        }
        return catalogRepository.pageTasks(new DeviceTaskQueryRequest(null, null, taskClass, 1L, 500L))
                .getRecords().stream()
                .filter(task -> taskClass.equals(task.taskClass()))
                .findFirst().orElse(null);
    }

    private BigDecimal e2AverageSeconds(String taskClass) {
        return switch (taskClass) {
            case "IG" -> BigDecimal.valueOf(18);
            case "VG" -> BigDecimal.valueOf(900);
            case "LL" -> BigDecimal.valueOf(12);
            case "FT" -> BigDecimal.valueOf(1800);
            case "EM" -> BigDecimal.valueOf(5);
            case "SP" -> BigDecimal.valueOf(30);
            default -> BigDecimal.valueOf(60);
        };
    }

    private List<Map<String, Object>> e2Teaser(List<Map<String, Object>> rows) {
        Map<String, Integer> deviceVram = new LinkedHashMap<>();
        deviceVram.put("cloud-share", 0);
        deviceVram.put("phone", 8);
        deviceVram.put("S1", 96);
        deviceVram.put("Pro", 192);
        deviceVram.put("Rack", 640);
        List<Map<String, Object>> teaser = new ArrayList<>();
        deviceVram.forEach((deviceClass, vram) -> {
            List<String> locked = new ArrayList<>();
            BigDecimal dailyPotential = BigDecimal.ZERO;
            for (Map<String, Object> row : rows) {
                boolean enabled = Boolean.TRUE.equals(row.get("enabled"));
                int minVram = ((Number) row.get("minVRAM")).intValue();
                if (enabled && minVram > vram) {
                    locked.add(String.valueOf(row.get("taskClass")));
                    dailyPotential = dailyPotential.add((BigDecimal) row.get("dailyPotential"));
                }
            }
            teaser.add(detail("deviceClass", deviceClass, "vram", vram, "lockedTasks", locked,
                    "dailyPotential", dailyPotential.setScale(4, RoundingMode.HALF_UP)));
        });
        return teaser;
    }

    private int parseVram(String raw) {
        if (!StringUtils.hasText(raw)) {
            return -1;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace("GB", "").trim();
        try {
            BigDecimal value = new BigDecimal(normalized);
            return value.signum() < 0 ? -1 : value.intValueExact();
        } catch (RuntimeException ex) {
            return -1;
        }
    }

    private BigDecimal parseDecimalOrNull(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String normalizeUpper(String raw) {
        return StringUtils.hasText(raw) ? raw.trim().toUpperCase(Locale.ROOT) : "";
    }

    private boolean isMonotonicPhoneTier(
            List<DevicePhoneTierRewardView> tiers,
            int changedTier,
            BigDecimal changedValue,
            Function<DevicePhoneTierRewardView, BigDecimal> getter) {
        BigDecimal previous = null;
        for (DevicePhoneTierRewardView row : tiers.stream()
                .sorted(java.util.Comparator.comparingInt(DevicePhoneTierRewardView::tier)).toList()) {
            BigDecimal current = row.tier() == changedTier ? changedValue : getter.apply(row);
            if (current == null || current.compareTo(BigDecimal.ZERO) <= 0
                    || (previous != null && current.compareTo(previous) < 0)) {
                return false;
            }
            previous = current;
        }
        return true;
    }

    private boolean coverageBelowRedline() {
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        return coverage == null || !coverage.reliable() || coverage.coverageRatio() == null
                || coverage.redlinePct() == null
                || coverage.coverageRatio().compareTo(coverage.redlinePct()) < 0;
    }

    private boolean e3AmplifiesFinancialOutflow(
            Map<String, String> before,
            Map<String, String> candidate,
            Set<String> changedKeys) {
        Set<String> higherMeansMoreOutflow = Set.of(
                "capacityBand1DeltaPct", "capacityBand2DeltaPct", "capacityBand3DeltaPct",
                "capacityFloorPct",
                "promoMult", "promoMaxPerSession", "tradeinMaxDevicesPerOrder");
        Set<String> lowerMeansMoreOutflow = Set.of(
                "promoCooldownDays", "promoDelaySeconds", "promoMinAgeDays");
        Set<String> enablingMeansMoreOutflow = Set.of(
                "tradeinEnabled");
        for (String key : changedKeys) {
            if (higherMeansMoreOutflow.contains(key)
                    && e3Decimal(candidate, key, "0").compareTo(e3Decimal(before, key, "0")) > 0) {
                return true;
            }
            if (lowerMeansMoreOutflow.contains(key)
                    && e3Decimal(candidate, key, "0").compareTo(e3Decimal(before, key, "0")) < 0) {
                return true;
            }
            if (enablingMeansMoreOutflow.contains(key)
                    && !Boolean.parseBoolean(before.getOrDefault(key, "false"))
                    && Boolean.parseBoolean(candidate.getOrDefault(key, "false"))) {
                return true;
            }
            if ("tradeinRequireHigherPrice".equals(key)
                    && Boolean.parseBoolean(before.getOrDefault(key, "true"))
                    && !Boolean.parseBoolean(candidate.getOrDefault(key, "true"))) {
                return true;
            }
            if ("eligibility".equals(key)
                    && e3EligibilityRank(candidate.get(key)) < e3EligibilityRank(before.get(key))) {
                return true;
            }
            if ("promoRoutes".equals(key)
                    && !"全部页面".equals(before.get(key))
                    && "全部页面".equals(candidate.get(key))) {
                return true;
            }
        }
        if (changedKeys.stream().anyMatch(Set.of("stageEarlyEnd", "stageMidEnd")::contains)
                && E3CapacityCurve.amplifiesAtAnyFutureMonth(before, candidate)) {
            return true;
        }
        Set<String> participationKeys = Set.of(
                "capacityApplyToPhone", "capacityApplyToCloudShare", "capacityApplyToPcGpu",
                "capacityApplyToS1", "capacityApplyToPro", "capacityApplyToProV2",
                "capacityApplyToRackP1", "capacityApplyToRackP2");
        if (changedKeys.stream().anyMatch(key -> participationKeys.contains(key)
                || key.startsWith("capacityBand") || "capacityFloorPct".equals(key)
                || "stageEarlyEnd".equals(key) || "stageMidEnd".equals(key))) {
            for (String key : participationKeys) {
                boolean beforeParticipates = Boolean.parseBoolean(before.getOrDefault(key, "false"));
                boolean candidateParticipates = Boolean.parseBoolean(candidate.getOrDefault(key, "false"));
                if (E3CapacityCurve.participationChangeAmplifies(
                        before, candidate, beforeParticipates, candidateParticipates)) {
                    return true;
                }
            }
        }
        if (changedKeys.stream().anyMatch(key -> key.startsWith("tradeinLadderCut")
                || key.startsWith("tradeinLadderCredit"))
                && e3TradeinLadderAmplifies(before, candidate)) {
            return true;
        }
        return false;
    }

    private boolean e3TradeinLadderAmplifies(
            Map<String, String> before, Map<String, String> candidate) {
        java.util.SortedSet<BigDecimal> boundaries = new java.util.TreeSet<>();
        boundaries.add(BigDecimal.ZERO);
        boundaries.add(BigDecimal.valueOf(101));
        for (int i = 1; i <= 4; i++) {
            boundaries.add(e3Decimal(before, "tradeinLadderCut" + i, String.valueOf(i * 25)));
            boundaries.add(e3Decimal(candidate, "tradeinLadderCut" + i, String.valueOf(i * 25)));
        }
        List<BigDecimal> ordered = List.copyOf(boundaries);
        java.util.SortedSet<BigDecimal> probes = new java.util.TreeSet<>(boundaries);
        for (int i = 1; i < ordered.size(); i++) {
            probes.add(ordered.get(i - 1).add(ordered.get(i))
                    .divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP));
        }
        return probes.stream().anyMatch(ratio ->
                e3TradeinCredit(candidate, ratio).compareTo(e3TradeinCredit(before, ratio)) > 0);
    }

    private BigDecimal e3TradeinCredit(Map<String, String> config, BigDecimal ratio) {
        for (int i = 1; i <= 4; i++) {
            BigDecimal cut = e3Decimal(config, "tradeinLadderCut" + i, String.valueOf(i * 25));
            if (ratio.compareTo(cut) <= 0) {
                return e3Decimal(config, "tradeinLadderCredit" + i, String.valueOf(90 - i * 15));
            }
        }
        return e3Decimal(config, "tradeinLadderCredit5", "15");
    }

    private int e3EligibilityRank(String value) {
        if (!StringUtils.hasText(value) || "全部用户".equals(value)) {
            return 0;
        }
        for (int level = 2; level <= 6; level++) {
            if (value.startsWith("L" + level + "+")) {
                return level;
            }
        }
        return 0;
    }

    private void recordE3ConfigChange(
            String aggregateId,
            Map<String, String> before,
            Map<String, String> after,
            String trustedOperator,
            String reason,
            String idempotencyKey,
            String source) {
        LocalDateTime effectiveAt = LocalDateTime.now(clock);
        Map<String, Object> payload = detail(
                "keys", List.copyOf(after.keySet()),
                "before", new LinkedHashMap<>(before),
                "after", new LinkedHashMap<>(after),
                "effective_at", effectiveAt.toString(),
                "operator", trustedOperator,
                "reason", reason,
                "source", source,
                "ts", clock.millis());
        outboxService.publish("E3_CONFIG", aggregateId, "admin.tradein_config_changed", payload);
        auditRequired("admin.tradein_config_changed", "DEVICE_E3_CONFIG", aggregateId, trustedOperator,
                detail("keys", List.copyOf(after.keySet()), "before", new LinkedHashMap<>(before),
                        "after", new LinkedHashMap<>(after), "effectiveAt", effectiveAt,
                        "reason", reason, "source", source, "idempotencyKey", idempotencyKey.trim()));
    }

    private void recordE2TaskPricingChange(
            String taskClass,
            String field,
            Object before,
            Object after,
            LocalDateTime effectiveAt,
            String trustedOperator,
            String reason,
            String idempotencyKey) {
        Map<String, Object> payload = detail(
                "taskClass", taskClass,
                "field", field,
                "before", before,
                "after", after,
                "effective_at", effectiveAt.toString(),
                "operator", trustedOperator,
                "reason", reason,
                "ts", clock.millis());
        outboxService.publish("E2_TASK_PRICING", taskClass, "admin.task_pricing_changed", payload);
        auditRequired("admin.task_pricing_changed", "E2_TASK_PRICING", taskClass, trustedOperator,
                detail("taskClass", taskClass, "field", field, "before", before, "after", after,
                        "effectiveAt", effectiveAt, "reason", reason, "idempotencyKey", idempotencyKey.trim()));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private <T> ApiResult<T> deviceIdempotent(
            String scope, String idempotencyKey, String resourceId, Object request, Supplier<ApiResult<T>> action) {
        return (ApiResult<T>) (ApiResult) idempotencyService.execute(
                scope,
                idempotencyKey.trim(),
                e1RequestHash(scope, resourceId, request),
                ApiResult.class,
                (Supplier) action);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private <T> ApiResult<T> e1Idempotent(
            String scope, String idempotencyKey, String resourceId, Object request, Supplier<ApiResult<T>> action) {
        return (ApiResult<T>) (ApiResult) idempotencyService.execute(
                scope,
                idempotencyKey.trim(),
                e1RequestHash(scope, resourceId, request),
                ApiResult.class,
                (Supplier) action);
    }

    private String e1RequestHash(String scope, String resourceId, Object request) {
        String canonical = scope + "\n" + normalizeText(resourceId) + "\n" + String.valueOf(request);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private void auditRequired(
            String action, String resourceType, String resourceId, String operator, Map<String, Object> detail) {
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
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

    // ===== A2 命令模式回放(批 6 E 域,复用批 0-5 AuditReplayable 框架) =====

    @Override
    public String domain() {
        return "E";
    }

    @Override
    public ApiResult<?> replay(AuditReplayCommand cmd, AuditReplayContext ctx) {
        // replay 本身不带 @Transactional（与 G 域 replay 一致）：A2 命令经
        // AdminIdempotencyService.execute() 的 @Transactional(REQUIRED) 包裹，回放、票据、锁与审计同事务。
        Map<String, Object> p = cmd.params() == null ? Map.of() : cmd.params();
        String operator = ctx.operator();
        String reason = ctx.reason();
        String idem = ctx.idempotencyKey();
        switch (cmd.op()) {
            case "e1_sku_create" -> {
                return createSku(idem, buildSkuUpsertRequest(p, reason, operator));
            }
            case "e1_sku_update" -> {
                return updateSku(str(p, "skuId"), idem, buildSkuUpsertRequest(p, reason, operator));
            }
            case "e1_sku_status" -> {
                DeviceSkuStatusRequest req = new DeviceSkuStatusRequest(str(p, "status"), reason, operator);
                return updateSkuStatus(str(p, "skuId"), idem, req);
            }
            case "e1_sku_delete" -> {
                // deleteSku 复用 DeviceSkuStatusRequest(仅消费 reason/operator,status 不读)
                DeviceSkuStatusRequest req = new DeviceSkuStatusRequest(str(p, "status"), reason, operator);
                return deleteSku(str(p, "skuId"), idem, req);
            }
            case "e1_review_create" -> {
                DeviceReviewUpsertRequest req = new DeviceReviewUpsertRequest(
                        str(p, "skuId"), str(p, "author"), intVal(p, "rating"), str(p, "content"),
                        str(p, "dateText"), str(p, "status"), reason, operator);
                return createReview(idem, req);
            }
            case "e1_review_update" -> {
                DeviceReviewUpsertRequest req = new DeviceReviewUpsertRequest(
                        str(p, "skuId"), str(p, "author"), intVal(p, "rating"), str(p, "content"),
                        str(p, "dateText"), str(p, "status"), reason, operator);
                return updateReview(str(p, "reviewId"), idem, req);
            }
            case "e1_review_status" -> {
                DeviceReviewStatusRequest req = new DeviceReviewStatusRequest(str(p, "status"), reason, operator);
                return updateReviewStatus(str(p, "reviewId"), idem, req);
            }
            case "e1_review_delete" -> {
                // deleteReview 复用 DeviceReviewStatusRequest(仅消费 reason/operator,status 不读)
                DeviceReviewStatusRequest req = new DeviceReviewStatusRequest(str(p, "status"), reason, operator);
                return deleteReview(str(p, "reviewId"), idem, req);
            }
            case "e1_phase_create" -> {
                DevicePhaseUpsertRequest req = buildPhaseUpsertRequest(p, reason, operator);
                return createE1Phase(idem, req);
            }
            case "e1_phase_patch" -> {
                DevicePhaseUpsertRequest req = buildPhaseUpsertRequest(p, reason, operator);
                return patchE1Phase(str(p, "phaseId"), idem, req);
            }
            case "e1_phase_archive" -> {
                DevicePhaseArchiveRequest req = new DevicePhaseArchiveRequest(reason, operator);
                return archiveE1Phase(str(p, "phaseId"), idem, req);
            }
            case "e1_gate_create" -> {
                DeviceGenerationGateUpsertRequest req = new DeviceGenerationGateUpsertRequest(
                        str(p, "skuId"), str(p, "name"), intVal(p, "releaseMonth"), str(p, "phase"),
                        boolVal(p, "eligibility"), intVal(p, "phaseOffset"),
                        boolVal(p, "forceUnlock"), str(p, "status"), reason, operator);
                return createE1GenerationGate(idem, req);
            }
            case "e1_gate_patch" -> {
                DeviceGenerationGatePatchRequest req = new DeviceGenerationGatePatchRequest(
                        str(p, "name"), intVal(p, "releaseMonth"), str(p, "phase"),
                        boolVal(p, "eligibility"), intVal(p, "phaseOffset"), boolVal(p, "forceUnlock"),
                        str(p, "status"), reason, operator);
                return patchE1GenerationGate(str(p, "skuId"), idem, req);
            }
            case "e1_gate_archive" -> {
                DeviceGenerationGateArchiveRequest req = new DeviceGenerationGateArchiveRequest(reason, operator);
                return archiveE1GenerationGate(str(p, "skuId"), idem, req);
            }
            case "e1_gate_field" -> {
                // updateE1GenerationGate 复用 E3ConfigUpdateRequest(key,value,reason,operator)
                E3ConfigUpdateRequest req = new E3ConfigUpdateRequest(str(p, "key"), str(p, "value"), reason, operator);
                return updateE1GenerationGate(idem, req);
            }
            case "e1_early_access_update" -> {
                DeviceEarlyAccessUpdateRequest req = new DeviceEarlyAccessUpdateRequest(
                        boolVal(p, "enabled"), intVal(p, "leadDays"), reason, operator);
                return updateE1EarlyAccess(idem, req);
            }
            case "e2_phone_tier" -> {
                DevicePhoneTierRewardUpdateRequest req = new DevicePhoneTierRewardUpdateRequest(
                        decimal(p, "dailyUsdt"), decimal(p, "dailyNex"), reason, operator);
                return updatePhoneTierReward(intVal(p, "tier"), idem, req);
            }
            case "e2_task_create" -> {
                DeviceTaskUpsertRequest req = buildTaskUpsertRequest(p, reason, operator);
                return createTask(idem, req);
            }
            case "e2_task_update" -> {
                DeviceTaskUpsertRequest req = buildTaskUpsertRequest(p, reason, operator);
                return updateTask(str(p, "taskId"), idem, req);
            }
            case "e2_task_price" -> {
                DeviceTaskPriceRequest req = new DeviceTaskPriceRequest(decimal(p, "price"), reason, operator);
                return updateTaskPrice(str(p, "taskId"), idem, req);
            }
            case "e2_task_status" -> {
                DeviceTaskStatusRequest req = new DeviceTaskStatusRequest(str(p, "status"), reason, operator);
                return updateTaskStatus(str(p, "taskId"), idem, req);
            }
            case "e2_task_delete" -> {
                // deleteTask 复用 DeviceTaskStatusRequest(仅消费 reason/operator,status 不读)
                DeviceTaskStatusRequest req = new DeviceTaskStatusRequest(str(p, "status"), reason, operator);
                return deleteTask(str(p, "taskId"), idem, req);
            }
            case "e3_config" -> {
                E3ConfigUpdateRequest req = new E3ConfigUpdateRequest(str(p, "key"), str(p, "value"), reason, operator);
                return updateE3Config(idem, req);
            }
            case "e3_config_batch" -> {
                Object values = p.get("values");
                if (!(values instanceof Map<?, ?> rawValues)) {
                    return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "E3_CONFIG_VALUES_REQUIRED");
                }
                Map<String, Object> batch = new LinkedHashMap<>();
                rawValues.forEach((key, value) -> batch.put(String.valueOf(key), value));
                return updateE3ConfigBatch(idem, batch, reason, operator);
            }
            case "e3_tradein" -> {
                // @Transactional(rollbackFor=Exception.class) 自调用陷阱:replay 本身不带 @Transactional(与 G 域 op7 一致),
                // 此处 this.executeTradeinAction(...) 是直接自调用,Spring 代理被绕过,
                // @Transactional(含显式 rollbackFor=Exception.class 配置)不生效。原子性回退由外层
                // AdminIdempotencyService.execute() 的 @Transactional(REQUIRED) 兜底：该方法业务失败走
                // ApiResult.fail 会由 A2 requireSuccess 转为 BizException；并发/状态冲突本身也抛 BizException，
                // 两者都会让 AdminIdempotencyService 的事务回滚。
                DeviceTradeinActionRequest req = new DeviceTradeinActionRequest(longVal(p, "deviceId"), reason, operator);
                return executeTradeinAction(str(p, "operation"), idem, req);
            }
            case "e3_restore" -> {
                DeviceRestoreRequest req = new DeviceRestoreRequest(reason, operator);
                return restoreDevice(longVal(p, "deviceId"), idem, req);
            }
            case "e5_device_activate" -> {
                return activateE5Device(longVal(p, "deviceId"), false, idem,
                        new DeviceE5ActionRequest(reason, operator));
            }
            case "e5_device_force_activate" -> {
                return activateE5Device(longVal(p, "deviceId"), true, idem,
                        new DeviceE5ActionRequest(reason, operator));
            }
            case "e5_device_deactivate" -> {
                return deactivateE5Device(longVal(p, "deviceId"), false, idem,
                        new DeviceE5ActionRequest(reason, operator));
            }
            case "e5_device_unbind" -> {
                return deactivateE5Device(longVal(p, "deviceId"), true, idem,
                        new DeviceE5ActionRequest(reason, operator));
            }
            case "e5_device_batch_pause" -> {
                return batchE5Devices(true, idem,
                        new DeviceE5BatchRequest(longVal(p, "userId"), reason, operator));
            }
            case "e5_device_batch_resume" -> {
                return batchE5Devices(false, idem,
                        new DeviceE5BatchRequest(longVal(p, "userId"), reason, operator));
            }
            case "e4_order_refund" -> {
                DeviceOrderActionRequest req = new DeviceOrderActionRequest(
                        str(p, "terminalState"), str(p, "refundChannel"), reason, operator);
                return refundOrder(str(p, "orderNo"), idem, req);
            }
            case "e4_order_cancel" -> {
                // cancelOrder 复用 DeviceOrderActionRequest(忽略 terminalState,内部硬编码 "cancelled")
                DeviceOrderActionRequest req = new DeviceOrderActionRequest(str(p, "terminalState"), reason, operator);
                return cancelOrder(str(p, "orderNo"), idem, req);
            }
            case "e4_order_terminal" -> {
                DeviceOrderActionRequest req = new DeviceOrderActionRequest(str(p, "terminalState"), reason, operator);
                return terminalOrder(str(p, "orderNo"), idem, req);
            }
            case "e4_order_state" -> {
                DeviceOrderStateRequest req = new DeviceOrderStateRequest(str(p, "state"), reason, operator);
                return updateOrderState(str(p, "orderNo"), idem, req);
            }
            case "e5_datacenter_create" -> {
                DeviceDatacenterUpsertRequest req = buildDatacenterUpsertRequest(p, reason, operator);
                return createDatacenter(idem, req);
            }
            case "e5_datacenter_update" -> {
                DeviceDatacenterUpsertRequest req = buildDatacenterUpsertRequest(p, reason, operator);
                String oldDcLocation = StringUtils.hasText(str(p, "oldDcLocation"))
                        ? str(p, "oldDcLocation") : str(p, "dcLocation");
                return updateDatacenter(oldDcLocation, idem, req);
            }
            case "e5_datacenter_delete" -> {
                DatacenterOpsRequest req = new DatacenterOpsRequest(reason, operator);
                return deleteDatacenter(str(p, "dcLocation"), idem, req);
            }
            case "e5_datacenter_pause" -> {
                DatacenterOpsRequest req = new DatacenterOpsRequest(reason, operator);
                return pauseDatacenter(str(p, "dcLocation"), idem, req);
            }
            case "e5_datacenter_resume" -> {
                DatacenterOpsRequest req = new DatacenterOpsRequest(reason, operator);
                return resumeDatacenter(str(p, "dcLocation"), idem, req);
            }
            case "e6_compute_config" -> {
                ComputeConfigParamUpdateRequest req = new ComputeConfigParamUpdateRequest(str(p, "value"), reason, operator);
                return updateComputeConfigParam(str(p, "paramKey"), idem, req);
            }
            case "e6_compute_config_batch" -> {
                return updateComputeConfigBatch(idem,
                        new ComputeConfigBatchUpdateRequest(stringMap(p, "values"), reason, operator));
            }
            default -> {
                return ApiResult.fail(422, "UNKNOWN_REPLAY_OP:" + cmd.op());
            }
        }
    }

    /** 从 replay params 重建 DeviceSkuUpsertRequest(e1_sku_create/update 复用)。 */
    private static DeviceSkuUpsertRequest buildSkuUpsertRequest(Map<String, Object> p, String reason, String operator) {
        return new DeviceSkuUpsertRequest(
                str(p, "skuId"), str(p, "name"), str(p, "tier"), str(p, "tagline"), str(p, "badge"),
                str(p, "gpu"), str(p, "vram"), str(p, "hashRate"), str(p, "power"), str(p, "datacenter"),
                decimal(p, "price"), decimal(p, "dailyEarn"), decimal(p, "dailyEarnNex"),
                decimal(p, "shareYieldMin"), decimal(p, "shareYieldMax"), str(p, "baseRate"),
                longVal(p, "sold"), str(p, "stock"), decimal(p, "rating"), longVal(p, "reviews"),
                longVal(p, "aiImageGenPerMin"), longVal(p, "aiLlmTokensPerSec"),
                longVal(p, "aiVideoMinPerHour"), longVal(p, "aiFineTuneMins"),
                str(p, "aiUnlocks"), strList(p, "features"), intVal(p, "generation"),
                str(p, "lifecycle"), str(p, "supersededBy"), decimal(p, "tradeinDiscount"),
                str(p, "unlockPhase"), buildPurchaseGate(p), str(p, "imageAssetId"),
                str(p, "imageObjectKey"), str(p, "imagePreviewUrl"), str(p, "tag"),
                str(p, "status"), reason, operator);
    }

    /** 从 replay params 的 purchaseGate 子 map 重建 DevicePurchaseGateView;缺失返回 null。 */
    private static DevicePurchaseGateView buildPurchaseGate(Map<String, Object> p) {
        Object raw = p.get("purchaseGate");
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        map.forEach((k, v) -> m.put(String.valueOf(k), v));
        return new DevicePurchaseGateView(
                intVal(m, "rankMin"), intVal(m, "activeDirectMin"), decimal(m, "teamVolumeMin"),
                str(m, "mode"), intVal(m, "quotaCap"), intVal(m, "quotaSold"),
                str(m, "quotaPeriod"), boolVal(m, "enforce"));
    }

    /** 从 replay params 重建 DevicePhaseUpsertRequest(e1_phase_create/patch 复用)。 */
    private static DevicePhaseUpsertRequest buildPhaseUpsertRequest(Map<String, Object> p, String reason, String operator) {
        return new DevicePhaseUpsertRequest(
                str(p, "phaseId"), str(p, "label"), str(p, "meta"), str(p, "skus"),
                intVal(p, "sortOrder"), str(p, "status"), reason, operator);
    }

    /** 从 replay params 重建 DeviceTaskUpsertRequest(e2_task_create/update 复用)。 */
    private static DeviceTaskUpsertRequest buildTaskUpsertRequest(Map<String, Object> p, String reason, String operator) {
        return new DeviceTaskUpsertRequest(
                str(p, "name"), decimal(p, "price"), str(p, "unit"), str(p, "requirement"),
                decimal(p, "saturation"), str(p, "status"), str(p, "taskClass"), str(p, "model"),
                decimal(p, "minReward"), decimal(p, "maxReward"), str(p, "minVram"), str(p, "killInit"),
                reason, operator);
    }

    /** 从 replay params 重建 DeviceDatacenterUpsertRequest(e5_datacenter_create/update 复用)。 */
    private static DeviceDatacenterUpsertRequest buildDatacenterUpsertRequest(Map<String, Object> p, String reason, String operator) {
        return new DeviceDatacenterUpsertRequest(
                str(p, "dcLocation"), str(p, "regionLabel"), str(p, "status"),
                intVal(p, "sortOrder"), reason, operator,
                str(p, "location"), str(p, "displayName"));
    }

    /** 从 replay params 取字符串,null 安全。 */
    private static String str(Map<String, Object> params, String key) {
        Object v = params.get(key);
        return v == null ? null : String.valueOf(v).trim();
    }

    private static Map<String, String> stringMap(Map<String, Object> params, String key) {
        Object raw = params.get(key);
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        map.forEach((k, v) -> values.put(String.valueOf(k).trim(), v == null ? "" : String.valueOf(v).trim()));
        return values;
    }

    /** 从 replay params 取 List<String>,null 安全(支持 List 与逗号分隔字符串)。 */
    private static List<String> strList(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (v == null) {
            return List.of();
        }
        if (v instanceof List<?> list) {
            return list.stream()
                    .map(item -> item == null ? "" : String.valueOf(item).trim())
                    .filter(StringUtils::hasText)
                    .toList();
        }
        String raw = String.valueOf(v).trim();
        if (raw.isEmpty()) {
            return List.of();
        }
        return java.util.Arrays.stream(raw.split("[,\\s]+"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    /** 从 replay params 取 Boolean,null 安全。 */
    private static Boolean boolVal(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s.trim());
        return null;
    }

    /** 从 replay params 取 Integer,null 安全(缺失返回 null,由 DTO 默认逻辑兜底)。 */
    private static Integer intVal(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 从 replay params 取 Long,null 安全(E 域 sku/task 含 Long 计数字段,deviceId 为 Long)。 */
    private static Long longVal(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 从 replay params 取 BigDecimal,null 安全(E 域 price/reward/discount 等大量 BigDecimal 字段)。 */
    private static BigDecimal decimal(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

}
