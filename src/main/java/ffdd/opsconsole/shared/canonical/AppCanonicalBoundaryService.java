package ffdd.opsconsole.shared.canonical;

import ffdd.opsconsole.risk.facade.TamperDetectionPublisher;
import ffdd.opsconsole.growth.application.AppGrowthLifecyclePublisher;
import ffdd.opsconsole.growth.application.AppGrowthLifecyclePublisher.UserAttribution;
import ffdd.opsconsole.growth.application.AppGrowthLifecyclePublisher.VoucherRedemption;
import ffdd.opsconsole.growth.facade.GrowthRhythmFacade;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import ffdd.opsconsole.commerce.mapper.CommerceAcceptanceSandboxMapper;
import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import ffdd.opsconsole.commerce.application.CommerceAcceptanceRun;
import ffdd.opsconsole.shared.capacity.E3DeviceCapacityPolicy;
import ffdd.opsconsole.device.domain.ProductInventoryMode;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.core.env.Environment;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AppCanonicalBoundaryService {
    private static final ZoneId SERVER_ZONE = ZoneId.of("Asia/Shanghai");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern SANDBOX_RUN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{7,95}");
    private final CanonicalStateMapper mapper;
    private final TamperDetectionPublisher tamperPublisher;
    private final AdminIdempotencyService idempotencyService;
    private final EventOutboxService outboxService;
    private final AppGrowthLifecyclePublisher growthLifecyclePublisher;
    private final GrowthRhythmFacade growthRhythmFacade;
    private final AuditLogService auditLogService;
    private final CommerceAcceptanceSandboxMapper commerceAcceptanceSandboxMapper;
    private final FundsSandboxProfileGuard fundsSandboxProfileGuard;
    private final CommerceAcceptanceRun commerceAcceptanceRun;
    private final StorefrontProductReleasePolicy productReleasePolicy;
    private final StorefrontPurchaseGatePolicy purchaseGatePolicy;
    private final Environment environment;

    @PostConstruct
    void ensureOtpChallengeTable() {
        mapper.createOtpChallengeTable();
    }

    public ApiResult<Map<String, Object>> trialEligibility(Long userId, String clientStatus) {
        String state = normalizeState(mapper.findTrialState(userId), "ELIGIBLE");
        if (StringUtils.hasText(clientStatus) && !state.equals(normalizeState(clientStatus, ""))) {
            return rejectTrialStateTamper(userId);
        }
        return ApiResult.ok(linked("state", state, "canStart", "ELIGIBLE".equals(state), "source", "nx_trial_claim"));
    }

    /**
     * Records the canonical H2 lifecycle rejection in J3. The controller calls this after comparing
     * the client claim with {@link ffdd.opsconsole.growth.application.AppTrialLifecycleService},
     * so J3 never has to infer the current trial state from a second projection.
     */
    public ApiResult<Map<String, Object>> rejectTrialStateTamper(Long userId) {
        return reject(userId, "free_trial_state",
                "客户端试用状态与服务器领取记录不一致，服务器拒绝按客户端状态重新领取",
                "/api/trial/eligibility", "TRIAL_STATE_CONFLICT");
    }

    public ApiResult<Map<String, Object>> securityState(Long userId, Boolean clientTwoFactorEnabled) {
        boolean enabled = mapper.twoFactorEnabled(userId);
        if (clientTwoFactorEnabled != null && clientTwoFactorEnabled != enabled) {
            return reject(userId, "two_factor_state",
                    "客户端 2FA 状态与服务器安全状态不一致，服务器拒绝使用伪造状态降级风控",
                    "/api/security/state", "TWO_FACTOR_STATE_CONFLICT");
        }
        return ApiResult.ok(linked("twoFactorEnabled", enabled, "source", "nx_user_security"));
    }

    public ApiResult<Map<String, Object>> productPhase(Long userId, String clientPinned, boolean devMode) {
        GrowthRhythmSnapshot rhythm = growthRhythmFacade.snapshot();
        if (!completeRhythm(rhythm)) {
            return ApiResult.fail(503, "H1_RHYTHM_UNAVAILABLE");
        }
        String phase = rhythm.currentPhase().trim().toUpperCase(Locale.ROOT);
        if (devMode || (StringUtils.hasText(clientPinned) && !phase.equals(normalizeState(clientPinned, "")))) {
            return reject(userId, "product_phase_override",
                    "客户端阶段锁定或开发模式参数与服务器当前阶段不一致，服务器拒绝覆盖阶段",
                    "/api/product/phase", "PRODUCT_PHASE_OVERRIDE_REJECTED");
        }
        return ApiResult.ok(linked(
                "phase", phase,
                "rhythm", rhythm.summary(),
                "dials", rhythm.dials(),
                "devOverrideAllowed", false,
                "source", "H1_GROWTH_RHYTHM"));
    }

    private boolean completeRhythm(GrowthRhythmSnapshot rhythm) {
        return rhythm != null
                && rhythm.totalMonths() > 0
                && rhythm.currentMonth() > 0
                && rhythm.currentMonth() <= rhythm.totalMonths()
                && StringUtils.hasText(rhythm.currentPhase())
                && rhythm.currentPhase().trim().toUpperCase(Locale.ROOT).matches("P[1-6]")
                && rhythm.phaseProgressPct() >= 0
                && rhythm.phaseProgressPct() <= 100
                && positive(rhythm.newUserBonusMultiplier())
                && positive(rhythm.inviteRewardMultiplier())
                && positive(rhythm.reinvestMultiplier())
                && bounded(rhythm.withdrawPenaltyFeeRate(), BigDecimal.ZERO, BigDecimal.valueOf(100))
                && rhythm.withdrawCooldownDays() > 0
                && bounded(rhythm.binaryDailyCap(), BigDecimal.ZERO, BigDecimal.valueOf(50_000))
                && positive(rhythm.questBonusMultiplier())
                && rhythm.sourceKeys() != null
                && !rhythm.sourceKeys().isEmpty();
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private boolean bounded(BigDecimal value, BigDecimal minimum, BigDecimal maximum) {
        return value != null && value.compareTo(minimum) >= 0 && value.compareTo(maximum) <= 0;
    }

    @Transactional
    public ApiResult<Map<String, Object>> activateDevice(
            Long userId, Long deviceId, Long expectedVersion, Integer clientMaxDevices, String idempotencyKey) {
        ApiResult<Map<String, Object>> gate = productionDeviceUserGate(userId);
        if (gate != null) return gate;
        return executeOnce("DEVICE_ACTIVATE", userId, idempotencyKey,
                linked("deviceId", deviceId, "expectedVersion", expectedVersion, "clientMaxDevices", clientMaxDevices),
                () -> activateDeviceInternal(userId, deviceId, expectedVersion, clientMaxDevices, idempotencyKey));
    }

    private ApiResult<Map<String, Object>> activateDeviceInternal(
            Long userId, Long deviceId, Long expectedVersion, Integer clientMaxDevices, String idempotencyKey) {
        if (deviceId == null || deviceId <= 0) return ApiResult.fail(422, "DEVICE_ID_REQUIRED");
        if (expectedVersion == null || expectedVersion < 0) return ApiResult.fail(422, "DEVICE_VERSION_REQUIRED");
        CanonicalStateMapper.UserDeviceCommandRow device = mapper.lockDeviceForUserCommand(deviceId);
        if (device == null) return ApiResult.fail(404, "DEVICE_NOT_FOUND");
        if (!userId.equals(device.userId()) || !"OWNED".equals(normalizeState(device.ownershipStatus(), ""))) {
            return ApiResult.fail(403, "DEVICE_FORBIDDEN");
        }
        if (!expectedVersion.equals(device.rowVersion())) return ApiResult.fail(409, "DEVICE_VERSION_CONFLICT");
        String status = normalizeState(device.status(), "");
        boolean occupiesPhysicalSlot = !"SHARE".equals(normalizeState(device.deviceType(), "DEVICE"));
        if ("ACTIVE".equals(status)) return ApiResult.ok(linked(
                "deviceId", device.id(), "instanceNo", device.instanceNo(), "status", "ACTIVE",
                "activeCount", Math.max(0, mapper.activeDeviceCount(userId)),
                "slotCap", Math.max(1, mapper.deviceSlotCap()), "rowVersion", device.rowVersion(),
                "alreadyActive", true));
        int cap = Math.max(1, mapper.deviceSlotCap());
        int active = Math.max(0, mapper.activeDeviceCount(userId));
        if ((clientMaxDevices != null && clientMaxDevices > cap)
                || (occupiesPhysicalSlot && active >= cap)) {
            return reject(userId, "device_slot_cap",
                    "客户端设备槽位上限高于服务器配置或账户已达上限，服务器拒绝激活",
                    "/api/devices/activate", "DEVICE_SLOT_CAP_EXCEEDED");
        }
        CanonicalStateMapper.UserEventAttribution attribution = mapper.userEventAttribution(userId);
        if (attribution == null || attribution.accountAgeMonths() == null || !StringUtils.hasText(attribution.cohort())) {
            throw new BizException(409, "USER_EVENT_ATTRIBUTION_UNAVAILABLE");
        }
        if (mapper.activateOwnedDeviceCas(userId, deviceId, expectedVersion, cap) != 1) {
            throw new BizException(409, "DEVICE_VERSION_CONFLICT");
        }
        long nextVersion = expectedVersion + 1;
        Map<String, Object> state = linked(
                "deviceId", device.id(), "instanceNo", device.instanceNo(), "status", "ACTIVE",
                "activeCount", active + (occupiesPhysicalSlot ? 1 : 0),
                "slotCap", cap, "rowVersion", nextVersion, "alreadyActive", false);
        outboxService.publishUserEvent(
                "USER_DEVICE", device.instanceNo(), "device.activated", userId,
                normalizePhase(attribution.phase()), attribution.accountAgeMonths(), attribution.cohort(),
                linked("userId", userId, "deviceId", device.id(), "instanceNo", device.instanceNo(),
                        "previousStatus", status, "status", "ACTIVE", "rowVersion", nextVersion));
        auditLogService.recordRequiredForTrustedActor(AuditLogWriteRequest.builder()
                .action("USER_DEVICE_ACTIVATED").resourceType("USER_DEVICE")
                .resourceId(String.valueOf(device.id())).bizNo(device.instanceNo())
                .userId(userId).actorId(userId).actorType("USER").actorUsername("user:" + userId)
                .method("POST").path("/api/devices/activate")
                .result("SUCCESS").riskLevel("MEDIUM")
                .detail(linked("idempotencyKey", idempotencyKey == null ? "" : idempotencyKey.trim(), "state", state))
                .build());
        return ApiResult.ok(state);
    }

    @Transactional
    public ApiResult<Map<String, Object>> deactivateDevice(
            Long userId, Long deviceId, Long expectedVersion, String idempotencyKey) {
        ApiResult<Map<String, Object>> gate = productionDeviceUserGate(userId);
        if (gate != null) return gate;
        return executeOnce("DEVICE_DEACTIVATE", userId, idempotencyKey,
                linked("deviceId", deviceId, "expectedVersion", expectedVersion),
                () -> deactivateDeviceInternal(userId, deviceId, expectedVersion, idempotencyKey));
    }

    @Transactional
    public ApiResult<Map<String, Object>> deactivateAfterTask(
            Long userId, Long deviceId, Long expectedVersion, String idempotencyKey) {
        ApiResult<Map<String, Object>> gate = productionDeviceUserGate(userId);
        if (gate != null) return gate;
        return executeOnce("DEVICE_DEACTIVATE_AFTER_TASK", userId, idempotencyKey,
                linked("deviceId", deviceId, "expectedVersion", expectedVersion),
                () -> deactivateAfterTaskInternal(userId, deviceId, expectedVersion, idempotencyKey));
    }

    private ApiResult<Map<String, Object>> deactivateAfterTaskInternal(
            Long userId, Long deviceId, Long expectedVersion, String idempotencyKey) {
        if (deviceId == null || deviceId <= 0) return ApiResult.fail(422, "DEVICE_ID_REQUIRED");
        if (expectedVersion == null || expectedVersion < 0) return ApiResult.fail(422, "DEVICE_VERSION_REQUIRED");
        CanonicalStateMapper.UserDeviceCommandRow device = mapper.lockDeviceForUserCommand(deviceId);
        if (device == null) return ApiResult.fail(404, "DEVICE_NOT_FOUND");
        if (!userId.equals(device.userId()) || !"OWNED".equals(normalizeState(device.ownershipStatus(), ""))) {
            return ApiResult.fail(403, "DEVICE_FORBIDDEN");
        }
        if ("DEACTIVATED".equals(normalizeState(device.status(), ""))) {
            return ApiResult.ok(linked("deviceId", device.id(), "instanceNo", device.instanceNo(), "status", "DEACTIVATED",
                    "rowVersion", device.rowVersion(), "alreadyDeactivated", true));
        }
        if (!isSlotOccupyingActiveState(device.status())) return ApiResult.fail(409, "DEVICE_STATE_CONFLICT");
        if (!expectedVersion.equals(device.rowVersion())) return ApiResult.fail(409, "DEVICE_VERSION_CONFLICT");
        if (!mapper.hasActiveTask(userId, deviceId)) return deactivateDeviceInternal(userId, deviceId, expectedVersion, idempotencyKey);
        if (device.pendingDeactivate()) return ApiResult.ok(linked("deviceId", device.id(), "instanceNo", device.instanceNo(), "status", "PENDING_DEACTIVATE",
                "rowVersion", device.rowVersion(), "alreadyPending", true));
        if (mapper.markDevicePendingDeactivate(userId, deviceId, expectedVersion) != 1) {
            throw new BizException(409, "DEVICE_VERSION_CONFLICT");
        }
        Map<String, Object> state = linked("deviceId", device.id(), "instanceNo", device.instanceNo(),
                "status", "PENDING_DEACTIVATE", "rowVersion", device.rowVersion(), "pendingDeactivate", true,
                "alreadyPending", false);
        auditLogService.recordRequiredForTrustedActor(AuditLogWriteRequest.builder()
                .action("USER_DEVICE_DEACTIVATE_PENDING").resourceType("USER_DEVICE")
                .resourceId(String.valueOf(device.id())).bizNo(device.instanceNo()).userId(userId)
                .actorId(userId).actorType("USER").actorUsername("user:" + userId).method("POST")
                .path("/api/device/" + device.id() + "/deactivate-after-task").result("SUCCESS")
                .riskLevel("MEDIUM").detail(state).build());
        return ApiResult.ok(state);
    }

    private ApiResult<Map<String, Object>> deactivateDeviceInternal(
            Long userId, Long deviceId, Long expectedVersion, String idempotencyKey) {
        if (deviceId == null || deviceId <= 0) return ApiResult.fail(422, "DEVICE_ID_REQUIRED");
        if (expectedVersion == null || expectedVersion < 0) return ApiResult.fail(422, "DEVICE_VERSION_REQUIRED");
        CanonicalStateMapper.UserDeviceCommandRow device = mapper.lockDeviceForUserCommand(deviceId);
        if (device == null) return ApiResult.fail(404, "DEVICE_NOT_FOUND");
        if (!userId.equals(device.userId()) || !"OWNED".equals(normalizeState(device.ownershipStatus(), ""))) {
            return ApiResult.fail(403, "DEVICE_FORBIDDEN");
        }
        String status = normalizeState(device.status(), "");
        if ("DEACTIVATED".equals(status)) {
            return ApiResult.ok(linked(
                    "deviceId", device.id(), "instanceNo", device.instanceNo(), "status", "DEACTIVATED",
                    "rowVersion", device.rowVersion(), "alreadyDeactivated", true));
        }
        if (!isSlotOccupyingActiveState(status)) return ApiResult.fail(409, "DEVICE_STATE_CONFLICT");
        if (!expectedVersion.equals(device.rowVersion())) return ApiResult.fail(409, "DEVICE_VERSION_CONFLICT");

        CanonicalStateMapper.UserEventAttribution attribution = mapper.userEventAttribution(userId);
        if (attribution == null || attribution.accountAgeMonths() == null || !StringUtils.hasText(attribution.cohort())) {
            throw new BizException(409, "USER_EVENT_ATTRIBUTION_UNAVAILABLE");
        }
        if (mapper.deactivateOwnedDeviceCas(userId, deviceId, expectedVersion) != 1) {
            throw new BizException(409, "DEVICE_VERSION_CONFLICT");
        }
        // Force deactivation forfeits only the currently uncompleted tasks.
        // Keep this in the same transaction as device CAS, runtime and A4:
        // an event/audit failure must not leave either side partially stopped.
        mapper.cancelActiveDeviceTasks(userId, deviceId);
        mapper.markDeviceRuntimeDeactivated(deviceId);
        long nextVersion = expectedVersion + 1;
        Map<String, Object> state = linked(
                "deviceId", device.id(), "instanceNo", device.instanceNo(), "status", "DEACTIVATED",
                "rowVersion", nextVersion, "alreadyDeactivated", false);
        outboxService.publishUserEvent(
                "USER_DEVICE", device.instanceNo(), "device.deactivated", userId,
                normalizePhase(attribution.phase()), attribution.accountAgeMonths(), attribution.cohort(),
                linked("userId", userId, "deviceId", device.id(), "instanceNo", device.instanceNo(),
                        "previousStatus", status, "status", "DEACTIVATED", "rowVersion", nextVersion));
        auditLogService.recordRequiredForTrustedActor(AuditLogWriteRequest.builder()
                .action("USER_DEVICE_DEACTIVATED").resourceType("USER_DEVICE")
                .resourceId(String.valueOf(device.id())).bizNo(device.instanceNo())
                .userId(userId).actorId(userId).actorType("USER").actorUsername("user:" + userId)
                .method("POST").path("/api/device/" + device.id() + "/deactivate")
                .result("SUCCESS").riskLevel("MEDIUM")
                .detail(linked("idempotencyKey", idempotencyKey == null ? "" : idempotencyKey.trim(), "state", state))
                .build());
        return ApiResult.ok(state);
    }

    private boolean isSlotOccupyingActiveState(String status) {
        return Set.of("ACTIVE", "ONLINE", "BUSY", "RUNNING")
                .contains(normalizeState(status, ""));
    }

    public ApiResult<Map<String, Object>> deviceEarnings(
            Long userId, boolean seedLegacyDevice, boolean fastForwardAll, BigDecimal bumpedEarningsTotal) {
        boolean developmentRuntime = false;
        boolean sandboxRuntime = isAcceptanceSandboxRuntime();
        if (!developmentRuntime && !sandboxRuntime && !isProductionRuntime()) {
            return ApiResult.fail(503, "CANONICAL_DEVICE_RUNTIME_UNSUPPORTED");
        }
        String sourceEnvironment = sandboxRuntime ? "SANDBOX" : "PRODUCTION";
        String runId = sandboxRuntime ? sandboxRunId() : "";
        if (developmentRuntime) {
            Integer userEnvironment = mapper.activeUserEnvironment(userId);
            if (userEnvironment == null) return ApiResult.fail(404, "USER_NOT_FOUND");
            if (userEnvironment != 1) return ApiResult.fail(403, "CANONICAL_DEVELOPMENT_USER_REQUIRED");
        } else if (!sandboxRuntime) {
            ApiResult<Map<String, Object>> gate = productionDeviceUserGate(userId);
            if (gate != null) return gate;
        }
        if (seedLegacyDevice || fastForwardAll || bumpedEarningsTotal != null) {
            return reject(userId, "dev_seed_state",
                    "客户端设备/收益造数参数不被服务端采信，服务端拒绝按客户端状态修改设备收益",
                    "/api/devices/earnings", "DEV_SEED_STATE_REJECTED");
        }
        List<CanonicalStateMapper.E3CapacityConfig> configRows = mapper.e3CapacityConfig();
        Map<String, String> capacityConfig = configRows == null ? Map.of() : configRows.stream()
                .collect(Collectors.toMap(
                        CanonicalStateMapper.E3CapacityConfig::configKey,
                        CanonicalStateMapper.E3CapacityConfig::configValue,
                        (left, right) -> right,
                        LinkedHashMap::new));
        if (!validE3CapacityConfig(capacityConfig)) {
            return ApiResult.fail(409, "E3_CAPACITY_CONFIG_INCOMPLETE");
        }
        CanonicalStateMapper.UserCanonicalProfile profile = sandboxRuntime
                ? mapper.sandboxUserCanonicalProfile(userId)
                : developmentRuntime
                        ? mapper.developmentUserCanonicalProfile(userId)
                        : mapper.userCanonicalProfile(userId);
        if (profile == null || profile.joinedAt() == null) {
            return ApiResult.fail(409, "CANONICAL_USER_PROFILE_UNAVAILABLE");
        }
        List<CanonicalStateMapper.OwnedDevice> rawDevices = sandboxRuntime
                ? mapper.sandboxOwnedDevices(userId, runId)
                : developmentRuntime
                        ? mapper.developmentOwnedDevices(userId)
                        : mapper.ownedDevices(userId);
        if (rawDevices != null) {
            for (CanonicalStateMapper.OwnedDevice device : rawDevices) {
                try {
                    validateDeviceSpec(device);
                } catch (BizException invalid) {
                    return ApiResult.fail(invalid.getCode(), invalid.getMessage());
                }
            }
        }
        LocalDateTime capacityNow = LocalDateTime.now(SERVER_ZONE);
        LocalDate today = capacityNow.toLocalDate();
        List<CanonicalStateMapper.DeviceRealizedToday> realizedRows = sandboxRuntime
                ? List.of()
                : developmentRuntime
                        ? mapper.developmentRealizedToday(
                                userId, today.atStartOfDay(), today.plusDays(1).atStartOfDay())
                        : mapper.realizedToday(
                                userId, today.atStartOfDay(), today.plusDays(1).atStartOfDay());
        if (realizedRows == null) return ApiResult.fail(503, "E3_REALIZED_EARNINGS_UNAVAILABLE");
        Map<Long, CanonicalStateMapper.DeviceRealizedToday> realizedByDevice = new LinkedHashMap<>();
        for (CanonicalStateMapper.DeviceRealizedToday row : realizedRows) {
            if (row == null || row.deviceId() == null || row.deviceId() <= 0
                    || row.todayEarningsUsdt() == null || row.todayEarningsUsdt().signum() < 0
                    || row.todayEarningsNex() == null || row.todayEarningsNex().signum() < 0
                    || realizedByDevice.put(row.deviceId(), row) != null) {
                return ApiResult.fail(503, "E3_REALIZED_EARNINGS_INVALID");
            }
        }
        List<Map<String, Object>> devices = (rawDevices == null ? List.<CanonicalStateMapper.OwnedDevice>of() : rawDevices)
                .stream().map(device -> {
                    Map<String, Object> projection = projectE3Capacity(device, capacityConfig, capacityNow);
                    CanonicalStateMapper.DeviceRealizedToday realized = realizedByDevice.get(device.id());
                    projection.put("todayEarningsUsdt", realized == null
                            ? BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP)
                            : realized.todayEarningsUsdt().setScale(6, RoundingMode.HALF_UP));
                    projection.put("todayEarningsNex", realized == null
                            ? BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP)
                            : realized.todayEarningsNex().setScale(6, RoundingMode.HALF_UP));
                    return projection;
                }).toList();
        BigDecimal realizedTodayUsdt = devices.stream()
                .map(device -> (BigDecimal) device.get("todayEarningsUsdt"))
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(6, RoundingMode.HALF_UP);
        BigDecimal realizedTodayNex = devices.stream()
                .map(device -> (BigDecimal) device.get("todayEarningsNex"))
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(6, RoundingMode.HALF_UP);
        BigDecimal usdt = devices.stream()
                .filter(device -> "ACTIVE".equalsIgnoreCase(String.valueOf(device.get("status"))))
                .map(device -> (BigDecimal) device.get("dailyUsdt"))
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(6, RoundingMode.HALF_UP);
        BigDecimal nex = devices.stream()
                .filter(device -> "ACTIVE".equalsIgnoreCase(String.valueOf(device.get("status"))))
                .map(device -> (BigDecimal) device.get("dailyNex"))
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(6, RoundingMode.HALF_UP);
        return ApiResult.ok(linked(
                "dailyUsdt", usdt,
                "dailyNex", nex,
                "realizedTodayUsdt", realizedTodayUsdt,
                "realizedTodayNex", realizedTodayNex,
                "walletUsdt", zero(profile.usdtAvailable()).setScale(6, RoundingMode.HALF_UP),
                "walletNex", zero(profile.nexAvailable()).setScale(6, RoundingMode.HALF_UP),
                "userJoinedAt", profile.joinedAt().atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(),
                "serverNow", capacityNow.atZone(SERVER_ZONE).toInstant().toEpochMilli(),
                "timezone", SERVER_ZONE.getId(),
                "slotCap", Math.max(1, mapper.deviceSlotCap()),
                "devices", devices,
                "capacitySchedule", capacityConfig,
                "source", "nx_user_device + nx_compute_receipt + nx_compute_e3_config",
                "sourceEnvironment", sourceEnvironment,
                "runId", runId,
                "serverCanonical", true));
    }

    private boolean isAcceptanceSandboxRuntime() {
        return hasOnlyProfile("test");
    }

    private boolean isProductionRuntime() {
        String[] profiles = environment == null ? new String[0] : environment.getActiveProfiles();
        return profiles == null || profiles.length == 0 || hasOnlyProfile("dev") || hasOnlyProfile("prod");
    }

    private boolean hasOnlyProfile(String expected) {
        String[] profiles = environment == null ? new String[0] : environment.getActiveProfiles();
        return profiles != null && profiles.length == 1 && expected.equalsIgnoreCase(profiles[0]);
    }

    private String sandboxRunId() {
        String runId = environment == null ? "" : environment.getProperty("nexion.wheel.sandbox.run-id",
                environment.getProperty("NEXION_ACCEPTANCE_RUN_ID", ""));
        runId = runId == null ? "" : runId.trim();
        if (!SANDBOX_RUN_ID.matcher(runId).matches()) {
            throw new BizException(503, "CANONICAL_DEVICE_SANDBOX_RUN_ID_REQUIRED");
        }
        return runId;
    }

    private ApiResult<Map<String, Object>> productionDeviceUserGate(Long userId) {
        String[] profiles = environment == null ? new String[0] : environment.getActiveProfiles();
        if (FundsSandboxProfileGuard.isStrictIsolatedProfile(profiles)) {
            return ApiResult.fail(503, "CANONICAL_DEVICE_SANDBOX_UNAVAILABLE");
        }
        if (profiles != null && profiles.length > 0
                && !(profiles.length == 1 && java.util.Set.of("dev", "prod").contains(profiles[0].trim().toLowerCase(java.util.Locale.ROOT)))) {
            return ApiResult.fail(503, "CANONICAL_DEVICE_RUNTIME_UNSUPPORTED");
        }
        CanonicalStateMapper.UserLock user = mapper.lockUser(userId);
        if (user == null) return ApiResult.fail(404, "USER_NOT_FOUND");
        if (user.sandbox()) return ApiResult.fail(403, "CANONICAL_PRODUCTION_USER_REQUIRED");
        return null;
    }

    private boolean validE3CapacityConfig(Map<String, String> config) {
        return E3DeviceCapacityPolicy.validConfig(config);
    }

    private Map<String, Object> projectE3Capacity(
            CanonicalStateMapper.OwnedDevice device,
            Map<String, String> config,
            LocalDateTime now) {
        validateDeviceSpec(device);
        E3DeviceCapacityPolicy.Projection capacity;
        try {
            capacity = E3DeviceCapacityPolicy.project(
                    device.productCode(), device.deviceType(), device.purchasedAt(),
                    device.activatedAt(), now, config);
        } catch (IllegalArgumentException invalid) {
            throw new BizException(409, invalid.getMessage());
        }
        BigDecimal dailyUsdt = E3DeviceCapacityPolicy.applyCapacity(zero(device.dailyUsdt()), capacity);
        BigDecimal dailyNex = E3DeviceCapacityPolicy.applyCapacity(zero(device.dailyNex()), capacity);
        return linked(
                "id", device.id(), "rowVersion", device.rowVersion(),
                "pendingDeactivate", device.pendingDeactivate(),
                "instanceNo", device.instanceNo(), "name", device.name(),
                "deviceType", device.deviceType(), "productCode", device.productCode(), "status", device.status(),
                "activatedAt", epochMillis(device.activatedAt()), "purchasedAt", epochMillis(device.purchasedAt()),
                "dailyUsdt", dailyUsdt, "dailyNex", dailyNex,
                "gpuModel", device.gpuModel(), "vramTotalGb", device.vramTotalGb(),
                "basePowerW", device.basePowerW(), "location", device.location(),
                "actualPaidUsdt", zero(device.actualPaidUsdt()).setScale(6, RoundingMode.HALF_UP),
                "cumulativeOutputUsdt", zero(device.cumulativeOutputUsdt()).setScale(6, RoundingMode.HALF_UP),
                "capacityPct", capacity.capacityPct(), "capacityAgeMonths", capacity.ageMonths(),
                "capacityConfigKey", capacity.configKey(),
                "capacitySubsidized", capacity.subsidized(),
                "capacitySubsidyDays", capacity.subsidyDays(),
                "capacitySubsidyRemainingDays", capacity.subsidyRemainingDays(),
                "capacitySubsidyEndsAt", epochMillis(capacity.subsidyEndsAt()));
    }

    private void validateDeviceSpec(CanonicalStateMapper.OwnedDevice device) {
        if (device == null || device.id() == null || device.id() <= 0
                || !StringUtils.hasText(device.name())
                || !StringUtils.hasText(device.deviceType())
                || !StringUtils.hasText(device.productCode())
                || !StringUtils.hasText(device.gpuModel())
                || device.vramTotalGb() == null || device.vramTotalGb() < 0
                || device.basePowerW() == null || device.basePowerW().signum() < 0
                || !StringUtils.hasText(device.location())
                || device.dailyUsdt() == null || device.dailyUsdt().signum() < 0
                || device.dailyNex() == null || device.dailyNex().signum() < 0) {
            throw new BizException(409, "E3_DEVICE_SPEC_INCOMPLETE");
        }
    }

    private BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    @Transactional
    public ApiResult<Map<String, Object>> verifyOtp(
            Long userId, String challengeNo, String code, Boolean clientRegexAccepted, String idempotencyKey) {
        if (mapper.lockUser(userId) == null) return ApiResult.fail(404, "USER_NOT_FOUND");
        return executeOnce("OTP_VERIFY", userId, idempotencyKey,
                linked("challengeNo", challengeNo, "code", code, "clientRegexAccepted", clientRegexAccepted),
                () -> verifyOtpInternal(userId, challengeNo, code, clientRegexAccepted));
    }

    private ApiResult<Map<String, Object>> verifyOtpInternal(
            Long userId, String challengeNo, String code, Boolean clientRegexAccepted) {
        if (!StringUtils.hasText(challengeNo) || !StringUtils.hasText(code)) {
            return ApiResult.fail(422, "OTP_CHALLENGE_AND_CODE_REQUIRED");
        }
        String normalizedCode = code.trim();
        if (!normalizedCode.matches("\\d{6}") || mapper.consumeValidOtp(userId, challengeNo.trim(), normalizedCode) != 1) {
            mapper.incrementOtpFailure(userId, challengeNo.trim());
            if (Boolean.TRUE.equals(clientRegexAccepted) || normalizedCode.matches("\\d{6}")) {
                return reject(userId, "otp_verification",
                        "客户端正则认为验证码有效，但服务器 TTL、次数或摘要校验未通过",
                        "/api/auth/otp/verify", "OTP_VERIFICATION_REJECTED");
            }
            return ApiResult.fail(422, "OTP_FORMAT_INVALID");
        }
        return ApiResult.ok(linked("verified", true, "source", "nx_user_otp_challenge"));
    }

    @Transactional
    public ApiResult<Map<String, Object>> pushClientBill(
            Long userId, Map<String, Object> ignoredClientBill, String idempotencyKey) {
        if (mapper.lockUser(userId) == null) return ApiResult.fail(404, "USER_NOT_FOUND");
        return executeOnce("BILL_CLIENT_PUSH", userId, idempotencyKey, ignoredClientBill,
                () -> reject(userId, "bill_client_push",
                        "账单只能由服务器资金事件入账，服务器拒绝客户端推送账单",
                        "/api/wallet/bills", "CLIENT_BILL_PUSH_REJECTED"));
    }

    @Transactional
    public ApiResult<Map<String, Object>> createOrder(
            Long userId, String clientOrderId, Long productId, Integer quantity, String idempotencyKey) {
        return createOrder(userId, clientOrderId, productId, null, quantity, null, idempotencyKey);
    }

    @Transactional
    public ApiResult<Map<String, Object>> createOrder(
            Long userId, String clientOrderId, Long productId, String productNo,
            Integer quantity, String idempotencyKey) {
        return createOrder(userId, clientOrderId, productId, productNo, quantity, null, idempotencyKey);
    }

    @Transactional
    public ApiResult<Map<String, Object>> createOrder(
            Long userId, String clientOrderId, Long productId, String productNo,
            Integer quantity, String voucherId, String idempotencyKey) {
        boolean developmentRuntime = false;
        // Acceptance checkout has its own catalogue/order/inventory facts. Do this
        // before touching canonical order, product, voucher, or outbox boundaries.
        if (!developmentRuntime && commerceSandboxProfile()) {
            if (!isCommerceSandboxUser(userId)) return ApiResult.fail(403, "COMMERCE_SANDBOX_USER_REQUIRED");
            return executeSandboxOrderOnce(userId, idempotencyKey,
                    linked("clientOrderId", clientOrderId, "productId", productId,
                            "productNo", productNo, "quantity", quantity, "voucherId", voucherId),
                    () -> createSandboxOrderInternal(userId, clientOrderId, productId, productNo, quantity, voucherId));
        }
        if (!developmentRuntime && !fundsSandboxProfileGuard.isStrictProductionRuntime()) {
            return ApiResult.fail(503, "COMMERCE_SANDBOX_UNAVAILABLE");
        }
        if (developmentRuntime) {
            Integer userEnvironment = mapper.activeUserEnvironment(userId);
            if (userEnvironment == null) return ApiResult.fail(404, "USER_NOT_FOUND");
            if (userEnvironment != 1) return ApiResult.fail(403, "CANONICAL_DEVELOPMENT_USER_REQUIRED");
        } else {
            CanonicalStateMapper.UserLock user = mapper.lockUser(userId);
            if (user == null) return ApiResult.fail(404, "USER_NOT_FOUND");
            if (user.sandbox()) return ApiResult.fail(403, "COMMERCE_SANDBOX_USER_FORBIDDEN");
        }
        return executeOnce("ORDER_CREATE", userId, idempotencyKey,
                linked("clientOrderId", clientOrderId, "productId", productId,
                        "productNo", productNo, "quantity", quantity, "voucherId", voucherId),
                () -> createOrderInternal(
                        userId, clientOrderId, productId, productNo, quantity, voucherId, developmentRuntime));
    }

    public ApiResult<Map<String, Object>> orders(Long userId) {
        return ordersInternal(userId, null, 100, true);
    }

    public ApiResult<Map<String, Object>> orders(Long userId, String beforeOrderNo, Integer requestedPageSize) {
        int pageSize = requestedPageSize == null ? 50 : requestedPageSize;
        String cursor = beforeOrderNo == null ? null : beforeOrderNo.trim();
        if (pageSize < 1 || pageSize > 100 || (cursor != null && (cursor.isEmpty() || cursor.length() > 100))) {
            return ApiResult.fail(422, "ORDER_PAGE_INVALID");
        }
        return ordersInternal(userId, cursor, pageSize, false);
    }

    private ApiResult<Map<String, Object>> ordersInternal(
            Long userId, String beforeOrderNo, int pageSize, boolean legacyRead) {
        boolean developmentRuntime = false;
        if (developmentRuntime) {
            Integer userEnvironment = mapper.activeUserEnvironment(userId);
            if (userEnvironment == null) return ApiResult.fail(404, "USER_NOT_FOUND");
            if (userEnvironment != 1) return ApiResult.fail(403, "CANONICAL_DEVELOPMENT_USER_REQUIRED");
        } else if (commerceSandboxProfile()) {
            if (!isCommerceSandboxUser(userId)) return ApiResult.fail(403, "COMMERCE_SANDBOX_USER_REQUIRED");
            String runId = commerceAcceptanceRun.requireRunId();
            List<CommerceAcceptanceSandboxMapper.SandboxOrderView> snapshots = commerceAcceptanceSandboxMapper.listSandboxOrders(runId, userId);
            List<CommerceAcceptanceSandboxMapper.SandboxOrderView> all = snapshots == null
                    ? List.of() : snapshots;
            int start = 0;
            if (beforeOrderNo != null) {
                int cursorIndex = -1;
                for (int index = 0; index < all.size(); index++) {
                    if (beforeOrderNo.equals(all.get(index).orderNo())) { cursorIndex = index; break; }
                }
                if (cursorIndex < 0) return ApiResult.fail(422, "ORDER_PAGE_CURSOR_INVALID");
                start = cursorIndex + 1;
            }
            int end = Math.min(all.size(), start + pageSize);
            List<CommerceAcceptanceSandboxMapper.SandboxOrderView> page = all.subList(start, end);
            List<Map<String, Object>> sandboxOrders = page.stream()
                    .map(order -> projectSandboxOrder(order, runId, userId)).toList();
            String nextCursor = end < all.size() && !page.isEmpty() ? page.get(page.size() - 1).orderNo() : null;
            return ApiResult.ok(linked("orders", sandboxOrders, "source", "mock", "sourceEnvironment", "SANDBOX",
                    "runId", runId, "serverCanonical", true, "nextCursor", nextCursor));
        }
        if (!developmentRuntime && !fundsSandboxProfileGuard.isStrictProductionRuntime()) {
            return ApiResult.fail(503, "COMMERCE_SANDBOX_UNAVAILABLE");
        }
        if (!developmentRuntime) {
            CanonicalStateMapper.UserLock user = mapper.lockUser(userId);
            if (user == null) return ApiResult.fail(404, "USER_NOT_FOUND");
            if (user.sandbox()) return ApiResult.fail(403, "COMMERCE_SANDBOX_USER_FORBIDDEN");
        }
        List<CanonicalStateMapper.UserOrder> rows = legacyRead
                ? mapper.userOrders(userId)
                : mapper.userOrdersPage(userId, beforeOrderNo, pageSize + 1);
        Map<String, CommerceAcceptanceSandboxMapper.OrderOverlay> overlays = !developmentRuntime
                && fundsSandboxProfileGuard.isLocalSandboxEnabled()
                ? commerceAcceptanceSandboxMapper.listOrderOverlays(commerceAcceptanceRun.requireRunId(), userId).stream()
                .collect(Collectors.toMap(CommerceAcceptanceSandboxMapper.OrderOverlay::orderNo, value -> value, (left, right) -> left))
                : Map.of();
        List<CanonicalStateMapper.UserOrder> fetched = rows == null ? List.of() : rows;
        boolean hasMore = !legacyRead && fetched.size() > pageSize;
        List<CanonicalStateMapper.UserOrder> page = hasMore ? fetched.subList(0, pageSize) : fetched;
        List<Map<String, Object>> orders = page
                .stream().map(row -> projectUserOrder(row, overlays.get(row.orderNo()))).toList();
        String nextCursor = hasMore && !page.isEmpty() ? page.get(page.size() - 1).orderNo() : null;
        return ApiResult.ok(linked("orders", orders, "source", "server", "sourceEnvironment", "PRODUCTION",
                "runId", null, "serverCanonical", true, "nextCursor", nextCursor));
    }

    private Map<String, Object> projectUserOrder(CanonicalStateMapper.UserOrder order,
                                                  CommerceAcceptanceSandboxMapper.OrderOverlay overlay) {
        String sandboxState = overlay == null ? null : normalizeState(overlay.state(), "");
        String paymentStatus = overlayPaymentStatus(sandboxState, order.paymentStatus());
        String orderStatus = overlayOrderStatus(sandboxState, order.orderStatus());
        String activationStatus = overlayActivationStatus(sandboxState, order.activationStatus());
        LocalDateTime stateAt = overlay == null ? null : overlay.updatedAt();
        // Historical fulfillment used PAID/PAID/ACTIVE. Normalize only when
        // timestamps prove both payment and activation; preserve the stored row.
        if (overlay == null && "PAID".equals(paymentStatus) && "PAID".equals(orderStatus)
                && "ACTIVE".equals(activationStatus) && order.paidAt() != null
                && order.activatedAt() != null && !order.activatedAt().isBefore(order.paidAt())) {
            orderStatus = "COMPLETED";
            activationStatus = "ACTIVATED";
        }
        String canonicalStatus = canonicalOrderStatus(paymentStatus, orderStatus, activationStatus);
        LocalDateTime expiresAt = "placed".equals(canonicalStatus) && order.placedAt() != null
                ? order.placedAt().plusMinutes(pendingOrderTtlMinutes())
                : null;
        Map<String, Object> projected = linked(
                "orderNo", order.orderNo(),
                "productId", order.productId(),
                "productNo", order.productNo(),
                "productName", order.productName(),
                "quantity", order.quantity(),
                "itemCount", order.itemCount() == null ? order.quantity() : order.itemCount(),
                "subtotalUsdt", zero(order.subtotalUsdt()),
                "unitPriceUsdt", zero(order.unitPriceUsdt()),
                "discountUsdt", zero(order.discountUsdt()),
                "amountUsdt", zero(order.amountUsdt()),
                "paymentMethod", order.paymentMethod(),
                "paymentStatus", paymentStatus,
                "orderStatus", orderStatus,
                "activationStatus", activationStatus,
                "canonicalStatus", canonicalStatus,
                "orderType", normalizeState(order.orderType(), "SINGLE"),
                "placedAt", epochMillis(order.placedAt()),
                "expiresAt", epochMillis(expiresAt),
                "paidAt", epochMillis(order.paidAt() == null && "PAID".equals(paymentStatus) ? stateAt : order.paidAt()),
                "activatedAt", epochMillis(order.activatedAt() == null && "ACTIVATED".equals(activationStatus) ? stateAt : order.activatedAt()),
                "dataCenter", order.dataCenter(),
                "tradeinNo", order.tradeinNo(),
                "sourceDeviceId", order.sourceDeviceId(),
                "targetDeviceId", order.targetDeviceId(),
                "targetDeviceInstanceNo", order.targetDeviceInstanceNo(),
                "refundedAt", epochMillis(order.refundedAt()),
                "refundAmountUsdt", order.refundAmountUsdt() == null ? null : zero(order.refundAmountUsdt()),
                "refundChannel", order.refundChannel(),
                "refundBillNo", order.refundBillNo());
        if ("BUNDLE".equalsIgnoreCase(order.orderType())) {
            List<CanonicalStateMapper.UserOrderLineItem> lines = mapper.userOrderLineItems(order.orderNo());
            if (lines == null || lines.isEmpty()) throw new BizException(503, "BUNDLE_ORDER_LINES_UNAVAILABLE");
            projected.put("lineItems", lines.stream().map(line -> linked(
                    "sku", line.sku(), "name", line.name(), "quantity", line.quantity(),
                    "unitPriceUsdt", zero(line.unitPriceUsdt()), "lineAmountUsdt", zero(line.lineAmountUsdt())))
                    .toList());
        }
        return projected;
    }

    private String canonicalOrderStatus(String paymentStatus, String orderStatus, String activationStatus) {
        if (orderStatus.contains("CHARGEBACK") || paymentStatus.contains("CHARGEBACK")) return "chargeback";
        if (orderStatus.contains("REFUND") || paymentStatus.contains("REFUND")
                || activationStatus.contains("REFUND")) return "refunded";
        if (orderStatus.contains("PROVISIONING_FAILED")
                || activationStatus.contains("PROVISIONING_FAILED")) return "provisioning_failed";
        if (orderStatus.contains("PAYMENT_FAILED") || paymentStatus.contains("FAIL")) return "payment_failed";
        if (orderStatus.contains("EXPIRE") || paymentStatus.contains("EXPIRE")) return "expired";
        if (orderStatus.contains("CANCEL") || paymentStatus.contains("CANCEL")) return "cancelled";
        if ("COMPLETED".equals(orderStatus) || "ACTIVATED".equals(activationStatus)) return "activated";
        if ("PAID".equals(paymentStatus) && "WAITING_PROVISIONING".equals(activationStatus)) return "paid";
        if (activationStatus.contains("PROVISION") || orderStatus.contains("PROVISION")) return "provisioning";
        if ("PAID".equals(paymentStatus)) return "paid";
        return "placed";
    }

    private boolean purchaseGateAllowed(String rawGate, CanonicalStateMapper.PurchaseFacts facts) {
        if (!StringUtils.hasText(rawGate)) return true;
        try {
            StorefrontPurchaseGatePolicy.Decision decision = purchaseGatePolicy.evaluate(rawGate,
                    facts == null ? null : new StorefrontPurchaseGatePolicy.Facts(
                            facts.rank() == null ? 0 : facts.rank(),
                            facts.activeDirect() == null ? 0 : facts.activeDirect(),
                            facts.teamVolumeUsd() == null ? BigDecimal.ZERO : facts.teamVolumeUsd()));
            return decision.allowed();
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private QuotaReservation reserveCanonicalPurchaseQuota(Long userId, String productNo, int quantity) {
        String rawGate = mapper.purchaseGateJson(productNo);
        if (!StringUtils.hasText(rawGate)) return QuotaReservation.none();
        if (!purchaseGateAllowed(rawGate, mapper.purchaseFacts(userId))) {
            throw new BizException(409, "PURCHASE_GATE_BLOCKED");
        }
        boolean quotaReserved = purchaseGatePolicy.hasQuota(rawGate);
        if (quotaReserved) {
            if (mapper.consumePurchaseQuota(productNo, quantity) != 1) {
                throw new BizException(409, "PURCHASE_GATE_SOLD_OUT");
            }
            Long gateGeneration = mapper.lockPurchaseGateGeneration(productNo);
            if (gateGeneration == null || gateGeneration < 1) {
                throw new BizException(409, "PURCHASE_GATE_RESERVATION_UNAVAILABLE");
            }
            return new QuotaReservation(true, gateGeneration);
        }
        return QuotaReservation.none();
    }

    @Transactional(readOnly = true)
    public ApiResult<Map<String, Object>> purchaseEligibility(Long userId, String productNo) {
        String normalized = StringUtils.hasText(productNo) ? productNo.trim() : "";
        if (userId == null || userId <= 0 || !normalized.matches("[A-Za-z0-9._:-]{1,64}")) {
            return ApiResult.fail(422, "PURCHASE_ELIGIBILITY_REQUEST_INVALID");
        }
        ApiResult<Map<String, PurchaseEligibilityDecision>> batch =
                purchaseEligibilityBatch(userId, List.of(normalized));
        if (batch == null || batch.getCode() != 0) {
            return ApiResult.fail(batch == null ? 503 : batch.getCode(),
                    batch == null ? "PURCHASE_ELIGIBILITY_UNAVAILABLE" : batch.getMessage());
        }
        PurchaseEligibilityDecision decision = batch.getData() == null ? null : batch.getData().get(normalized);
        if (decision == null) return ApiResult.fail(404, "PRODUCT_NOT_AVAILABLE");
        boolean productionShaped = !fundsSandboxProfileGuard.isLocalSandboxEnabled();
        String runId = productionShaped ? null : commerceAcceptanceRun.requireRunId();
        return ApiResult.ok(linked("productNo", decision.productNo(), "eligible", decision.eligible(),
                "decisionCode", decision.decisionCode(),
                "evaluatedAt", System.currentTimeMillis(),
                "source", "nx_product + nx_admin_device_sku + nx_user",
                "sourceEnvironment", productionShaped ? "PRODUCTION" : "SANDBOX",
                "runId", runId, "serverCanonical", true));
    }

    /**
     * Evaluates a bounded storefront candidate set from one product snapshot and
     * one user-facts snapshot. Home/Earn uses this method so its conversion CTA
     * cannot drift from the detail/order gates or lock the same user once per SKU.
     */
    @Transactional(readOnly = true)
    public ApiResult<Map<String, PurchaseEligibilityDecision>> purchaseEligibilityBatch(
            Long userId, List<String> productNos) {
        if (userId == null || userId <= 0 || productNos == null || productNos.isEmpty() || productNos.size() > 20) {
            return ApiResult.fail(422, "PURCHASE_ELIGIBILITY_REQUEST_INVALID");
        }
        List<String> normalized = productNos.stream()
                .map(value -> StringUtils.hasText(value) ? value.trim() : "")
                .distinct()
                .toList();
        if (normalized.isEmpty() || normalized.stream()
                .anyMatch(value -> !value.matches("[A-Za-z0-9._:-]{1,64}"))) {
            return ApiResult.fail(422, "PURCHASE_ELIGIBILITY_REQUEST_INVALID");
        }
        if (fundsSandboxProfileGuard.isLocalSandboxEnabled()) {
            return sandboxPurchaseEligibilityBatch(userId, normalized);
        } else {
            requireProductionPurchaseEligibilityUser(userId);
        }
        List<CanonicalStateMapper.ProductStock> rows = mapper.findPurchasableProducts(normalized);
        if (rows == null) return ApiResult.fail(500, "PURCHASE_ELIGIBILITY_INVALID");
        Map<String, CanonicalStateMapper.ProductStock> products = rows.stream()
                .filter(java.util.Objects::nonNull)
                .filter(row -> normalized.contains(row.productNo()))
                .collect(Collectors.toMap(CanonicalStateMapper.ProductStock::productNo, row -> row,
                        (first, ignored) -> first, LinkedHashMap::new));
        Map<String, PurchaseEligibilityDecision> preliminary = new LinkedHashMap<>();
        Map<String, String> releaseCandidates = new LinkedHashMap<>();
        boolean needsFacts = false;
        for (String candidate : normalized) {
            CanonicalStateMapper.ProductStock product = products.get(candidate);
            if (product == null) continue;
            PurchaseEligibilityDecision decision = storefrontConfigurationDecision(
                    product.productNo(), product.productType(), product.inventoryMode(),
                    product.gpuModel(), product.vramTotalGb(), product.power(), product.datacenter());
            if (decision == null) releaseCandidates.put(product.productNo(), product.unlockPhase());
            else preliminary.put(product.productNo(), decision);
        }
        Map<String, StorefrontProductReleasePolicy.Decision> releases = releaseCandidates.isEmpty()
                ? Map.of() : productReleasePolicy.evaluateBatch(releaseCandidates);
        for (String candidate : releaseCandidates.keySet()) {
            StorefrontProductReleasePolicy.Decision release = releases == null ? null : releases.get(candidate);
            if (release == null || !release.available()) {
                preliminary.put(candidate, new PurchaseEligibilityDecision(candidate, false, "PRODUCT_NOT_RELEASED"));
            } else {
                needsFacts = true;
            }
        }
        CanonicalStateMapper.PurchaseFacts facts = needsFacts ? mapper.purchaseFacts(userId) : null;
        Map<String, PurchaseEligibilityDecision> decisions = new LinkedHashMap<>();
        for (String candidate : normalized) {
            CanonicalStateMapper.ProductStock product = products.get(candidate);
            if (product == null) continue;
            PurchaseEligibilityDecision decision = preliminary.get(candidate);
            decisions.put(candidate, decision != null ? decision : purchaseGateDecision(
                    product.productNo(), product.purchaseGateJson(),
                    facts == null ? null : new StorefrontPurchaseGatePolicy.Facts(
                            facts.rank() == null ? 0 : facts.rank(),
                            facts.activeDirect() == null ? 0 : facts.activeDirect(),
                            facts.teamVolumeUsd() == null ? BigDecimal.ZERO : facts.teamVolumeUsd())));
        }
        return ApiResult.ok(decisions);
    }

    private ApiResult<Map<String, PurchaseEligibilityDecision>> sandboxPurchaseEligibilityBatch(
            Long userId, List<String> productNos) {
        if (!commerceAcceptanceSandboxMapper.isSandboxUser(userId)) {
            return ApiResult.fail(403, "COMMERCE_SANDBOX_USER_REQUIRED");
        }
        String runId = commerceAcceptanceRun.requireRunId();
        List<CommerceAcceptanceSandboxMapper.SandboxEligibilityProduct> rows =
                commerceAcceptanceSandboxMapper.listSandboxEligibilityProducts(runId, productNos);
        if (rows == null) return ApiResult.fail(500, "PURCHASE_ELIGIBILITY_INVALID");
        Map<String, CommerceAcceptanceSandboxMapper.SandboxEligibilityProduct> products = rows.stream()
                .filter(java.util.Objects::nonNull)
                .filter(row -> productNos.contains(row.productNo()))
                .collect(Collectors.toMap(CommerceAcceptanceSandboxMapper.SandboxEligibilityProduct::productNo,
                        row -> row, (first, ignored) -> first, LinkedHashMap::new));
        Map<String, PurchaseEligibilityDecision> preliminary = new LinkedHashMap<>();
        Map<String, String> releaseCandidates = new LinkedHashMap<>();
        boolean needsFacts = false;
        for (String candidate : productNos) {
            CommerceAcceptanceSandboxMapper.SandboxEligibilityProduct product = products.get(candidate);
            if (product == null) continue;
            PurchaseEligibilityDecision decision = storefrontConfigurationDecision(
                    product.productNo(), product.productType(), product.inventoryMode(),
                    product.gpuModel(), product.vramTotalGb(), product.power(), product.datacenter());
            if (decision == null) releaseCandidates.put(product.productNo(), product.unlockPhase());
            else preliminary.put(product.productNo(), decision);
        }
        Map<String, StorefrontProductReleasePolicy.Decision> releases = releaseCandidates.isEmpty()
                ? Map.of() : productReleasePolicy.evaluateBatch(releaseCandidates);
        for (String candidate : releaseCandidates.keySet()) {
            StorefrontProductReleasePolicy.Decision release = releases == null ? null : releases.get(candidate);
            if (release == null || !release.available()) {
                preliminary.put(candidate, new PurchaseEligibilityDecision(candidate, false, "PRODUCT_NOT_RELEASED"));
            } else {
                needsFacts = true;
            }
        }
        CommerceAcceptanceSandboxMapper.PurchaseGateFacts facts = needsFacts
                ? commerceAcceptanceSandboxMapper.purchaseGateFacts(userId) : null;
        Map<String, PurchaseEligibilityDecision> decisions = new LinkedHashMap<>();
        for (String candidate : productNos) {
            CommerceAcceptanceSandboxMapper.SandboxEligibilityProduct product = products.get(candidate);
            if (product == null) continue;
            PurchaseEligibilityDecision decision = preliminary.get(candidate);
            decisions.put(candidate, decision != null ? decision : purchaseGateDecision(
                    product.productNo(), product.purchaseGateJson(),
                    facts == null ? null : new StorefrontPurchaseGatePolicy.Facts(
                            facts.rank() == null ? 0 : facts.rank(),
                            facts.activeDirect() == null ? 0 : facts.activeDirect(),
                            facts.teamVolumeUsd() == null ? BigDecimal.ZERO : facts.teamVolumeUsd())));
        }
        return ApiResult.ok(decisions);
    }

    private PurchaseEligibilityDecision storefrontConfigurationDecision(
            String productNo, String productType, String inventoryMode,
            String gpuModel, Integer vramTotalGb, String power, String datacenter) {
        String configurationBlock = storefrontConfigurationBlock(
                productType, inventoryMode, gpuModel, vramTotalGb, power, datacenter);
        if (configurationBlock != null) {
            return new PurchaseEligibilityDecision(productNo, false, configurationBlock);
        }
        return null;
    }

    private PurchaseEligibilityDecision purchaseGateDecision(
            String productNo, String purchaseGateJson, StorefrontPurchaseGatePolicy.Facts facts) {
        StorefrontPurchaseGatePolicy.Decision decision;
        try {
            decision = purchaseGatePolicy.evaluate(purchaseGateJson, facts);
        } catch (IllegalArgumentException ex) {
            decision = StorefrontPurchaseGatePolicy.Decision.closed("PURCHASE_GATE_FACTS_INVALID");
        }
        return new PurchaseEligibilityDecision(productNo, decision.allowed(),
                decision.allowed() ? "ELIGIBLE" : decision.code());
    }

    static String storefrontConfigurationBlock(
            String productType, String inventoryMode, String gpuModel, Integer vramTotalGb,
            String power, String datacenter) {
        String type = StringUtils.hasText(productType) ? productType.trim().toUpperCase(Locale.ROOT) : "";
        boolean physical = "DEVICE".equals(type) || "SERVER".equals(type);
        ProductInventoryMode mode = ProductInventoryMode.parse(inventoryMode);
        if (!(physical || "SHARE".equals(type))
                || mode == null || (mode == ProductInventoryMode.UNLIMITED && !"SHARE".equals(type))) {
            return "PRODUCT_INVENTORY_MODE_INVALID";
        }
        if (physical && (!StringUtils.hasText(gpuModel)
                || vramTotalGb == null || vramTotalGb <= 0
                || !StringUtils.hasText(power) || !StringUtils.hasText(datacenter))) {
            return "PRODUCT_SPECS_UNAVAILABLE";
        }
        return null;
    }

    private void requireProductionPurchaseEligibilityUser(Long userId) {
        if (!fundsSandboxProfileGuard.isStrictProductionRuntime()) {
            throw new BizException(503, "COMMERCE_SANDBOX_UNAVAILABLE");
        }
        Integer sandbox = mapper.activeUserEnvironment(userId);
        if (sandbox == null) throw new BizException(404, "USER_NOT_FOUND");
        if (sandbox != 0) throw new BizException(403, "COMMERCE_SANDBOX_USER_FORBIDDEN");
    }

    public record PurchaseEligibilityDecision(String productNo, boolean eligible, String decisionCode) { }

    private String overlayPaymentStatus(String state, String fallback) {
        if (!StringUtils.hasText(state)) return normalizeState(fallback, "PENDING");
        return switch (state) {
            case "PAID", "ACTIVATED", "PROVISIONING_FAILED" -> "PAID";
            case "PAYMENT_FAILED" -> "FAILED";
            case "EXPIRED" -> "EXPIRED";
            case "CANCELLED" -> "CANCELLED";
            case "REFUNDED" -> "REFUNDED";
            default -> normalizeState(fallback, "PENDING");
        };
    }

    private String overlayOrderStatus(String state, String fallback) {
        if (!StringUtils.hasText(state)) return normalizeState(fallback, "PENDING_PAYMENT");
        return switch (state) {
            case "PAID" -> "PAID";
            case "ACTIVATED" -> "COMPLETED";
            case "PAYMENT_FAILED", "EXPIRED", "PROVISIONING_FAILED", "REFUNDED", "CANCELLED" -> state;
            default -> normalizeState(fallback, "PENDING_PAYMENT");
        };
    }

    private String overlayActivationStatus(String state, String fallback) {
        if (!StringUtils.hasText(state)) return normalizeState(fallback, "WAITING_PAYMENT");
        return switch (state) {
            case "PAID" -> "WAITING_PROVISIONING";
            case "ACTIVATED" -> "ACTIVATED";
            case "PROVISIONING_FAILED", "REFUNDED", "CANCELLED" -> state;
            default -> normalizeState(fallback, "WAITING_PAYMENT");
        };
    }

    private Long epochMillis(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
    }

    private boolean isCommerceSandboxUser(Long userId) {
        return userId != null && userId > 0 && commerceAcceptanceSandboxMapper.isSandboxUser(userId);
    }

    private boolean commerceSandboxProfile() {
        return fundsSandboxProfileGuard.isLocalSandboxEnabled();
    }

    private Map<String, Object> projectSandboxOrder(CommerceAcceptanceSandboxMapper.SandboxOrderView order,
                                                    String runId, Long userId) {
        String state = normalizeState(order.state(), "PENDING_PAYMENT");
        String paymentStatus = overlayPaymentStatus(state, "PENDING");
        String orderStatus = overlayOrderStatus(state, "PENDING_PAYMENT");
        String activationStatus = overlayActivationStatus(state, "WAITING_PAYMENT");
        return linked("orderNo", order.orderNo(), "productId", order.productId(), "productNo", order.productNo(),
                "productName", order.productNo(), "quantity", order.quantity(), "unitPriceUsdt", zero(order.unitPriceUsdt()),
                "discountUsdt", BigDecimal.ZERO, "amountUsdt", zero(order.amountUsdt()), "paymentMethod", "SANDBOX_WALLET",
                "paymentStatus", paymentStatus, "orderStatus", orderStatus, "activationStatus", activationStatus,
                "canonicalStatus", canonicalOrderStatus(paymentStatus, orderStatus, activationStatus),
                "orderType", normalizeState(order.orderType(), "SINGLE"), "itemCount", order.itemCount(),
                "paymentNo", paidState(state) ? sandboxPaymentNo(runId, userId, order.orderNo()) : null,
                "placedAt", epochMillis(order.createdAt()), "paidAt", "PAID".equals(paymentStatus) ? epochMillis(order.updatedAt()) : null,
                "activatedAt", "ACTIVATED".equals(activationStatus) ? epochMillis(order.updatedAt()) : null,
                "dataCenter", null, "tradeinNo", null, "sourceDeviceId", null, "targetDeviceId", null,
                "targetDeviceInstanceNo", null);
    }

    private boolean paidState(String state) {
        return Set.of("PAID", "ACTIVATED", "PROVISIONING_FAILED", "REFUNDED").contains(state);
    }

    private String sandboxPaymentNo(String runId, Long userId, String orderNo) {
        return "PAY-SBX-" + sha256(runId + "|" + userId + "|" + orderNo)
                .substring(0, 32).toUpperCase(Locale.ROOT);
    }

    private ApiResult<Map<String, Object>> createSandboxOrderInternal(
            Long userId, String clientOrderId, Long productId, String productNo, Integer quantity, String voucherId) {
        if (StringUtils.hasText(clientOrderId)) return ApiResult.fail(422, "CLIENT_MINTED_ID_REJECTED");
        if (StringUtils.hasText(voucherId)) return ApiResult.fail(422, "COMMERCE_SANDBOX_VOUCHER_UNSUPPORTED");
        int qty = quantity == null ? 1 : quantity;
        String normalizedProductNo = StringUtils.hasText(productNo) ? productNo.trim() : null;
        boolean validProductId = productId != null && productId > 0;
        if ((!validProductId && normalizedProductNo == null) || qty < 1 || qty > 100) {
            return ApiResult.fail(422, "ORDER_PRODUCT_OR_QUANTITY_INVALID");
        }
        CommerceAcceptanceSandboxMapper.SandboxCatalogProduct product = commerceAcceptanceSandboxMapper
                .lockSandboxCatalogProduct(
                        commerceAcceptanceRun.requireRunId(), validProductId ? productId : null, normalizedProductNo, qty);
        if (product == null || product.priceUsdt() == null || product.priceUsdt().signum() <= 0
                || product.version() == null) {
            return ApiResult.fail(409, "COMMERCE_SANDBOX_PRODUCT_NOT_AVAILABLE");
        }
        String configurationBlock = storefrontConfigurationBlock(
                product.productType(), product.inventoryMode(), product.gpuModel(), product.vramTotalGb(),
                product.power(), product.datacenter());
        if (configurationBlock != null) return ApiResult.fail(409, configurationBlock);
        ProductInventoryMode inventoryMode = ProductInventoryMode.parse(product.inventoryMode());
        if (inventoryMode == ProductInventoryMode.FINITE
                && (product.stock() == null || product.stock() < qty)) {
            return ApiResult.fail(409, "COMMERCE_SANDBOX_PRODUCT_NOT_AVAILABLE");
        }
        CommerceAcceptanceSandboxMapper.PurchaseGateFacts sandboxFacts =
                commerceAcceptanceSandboxMapper.purchaseGateFacts(userId);
        if (StringUtils.hasText(product.purchaseGateJson())
                && !purchaseGateAllowed(product.purchaseGateJson(), sandboxFacts == null ? null
                        : new CanonicalStateMapper.PurchaseFacts(sandboxFacts.rank(), sandboxFacts.activeDirect(),
                                sandboxFacts.teamVolumeUsd()))) {
            return ApiResult.fail(409, "COMMERCE_SANDBOX_PURCHASE_GATE_BLOCKED");
        }
        StorefrontProductReleasePolicy.Decision release =
                productReleasePolicy.evaluate(product.productNo(), product.unlockPhase());
        if (!release.available()) {
            return ApiResult.fail(409, "COMMERCE_SANDBOX_PRODUCT_NOT_RELEASED");
        }
        BigDecimal amount = product.priceUsdt().multiply(BigDecimal.valueOf(qty)).setScale(6, RoundingMode.DOWN);
        String orderNo = "CSO-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        String runId = commerceAcceptanceRun.requireRunId();
        if (commerceAcceptanceSandboxMapper.reserveSandboxCatalogStock(runId, product.productId(), product.version(), qty) != 1) {
            throw new BizException(409, "COMMERCE_SANDBOX_STOCK_CONFLICT");
        }
        if (commerceAcceptanceSandboxMapper.insertSandboxOrder(new CommerceAcceptanceSandboxMapper.OrderWrite(
                orderNo, userId, product.productId(), qty, amount, product.version(), runId)) != 1
                || commerceAcceptanceSandboxMapper.insertInventory(new CommerceAcceptanceSandboxMapper.InventoryWrite(
                orderNo, product.productId(), product.productNo(), product.priceUsdt(), qty, runId)) != 1) {
            throw new BizException(409, "COMMERCE_SANDBOX_ORDER_CREATE_CONFLICT");
        }
        return ApiResult.ok(linked("orderNo", orderNo, "subtotalUsdt", amount, "discountUsdt", BigDecimal.ZERO,
                "amountUsdt", amount, "voucherId", null, "voucherRedemption", null, "paymentStatus", "PENDING",
                "orderStatus", "PENDING_PAYMENT", "idSource", "sandbox-server", "source", "mock",
                "sourceEnvironment", "SANDBOX", "runId", runId));
    }

    private ApiResult<Map<String, Object>> createOrderInternal(
            Long userId, String clientOrderId, Long productId, String productNo,
            Integer quantity, String voucherId, boolean developmentRuntime) {
        if (StringUtils.hasText(clientOrderId)) {
            return reject(userId, "client_minted_id",
                    "客户端提交了自铸业务 ID，服务器拒绝使用该 ID 创建订单",
                    "/api/orders", "CLIENT_MINTED_ID_REJECTED");
        }
        int qty = quantity == null ? 1 : quantity;
        String normalizedProductNo = StringUtils.hasText(productNo) ? productNo.trim() : null;
        boolean validProductId = productId != null && productId > 0;
        if ((!validProductId && normalizedProductNo == null) || qty < 1 || qty > 100) {
            return ApiResult.fail(422, "ORDER_PRODUCT_OR_QUANTITY_INVALID");
        }
        CanonicalStateMapper.ProductStock product = mapper.lockProduct(validProductId ? productId : null, normalizedProductNo);
        if (product == null || product.priceUsdt() == null || product.priceUsdt().signum() <= 0) {
            return ApiResult.fail(409, "PRODUCT_NOT_AVAILABLE");
        }
        String configurationBlock = storefrontConfigurationBlock(
                product.productType(), product.inventoryMode(), product.gpuModel(), product.vramTotalGb(),
                product.power(), product.datacenter());
        if (configurationBlock != null) return ApiResult.fail(409, configurationBlock);
        ProductInventoryMode inventoryMode = ProductInventoryMode.parse(product.inventoryMode());
        if (StringUtils.hasText(product.purchaseGateJson())
                && !purchaseGateAllowed(product.purchaseGateJson(), mapper.purchaseFacts(userId))) {
            return ApiResult.fail(409, "PURCHASE_GATE_BLOCKED");
        }
        StorefrontProductReleasePolicy.Decision release =
                productReleasePolicy.evaluate(product.productNo(), product.unlockPhase());
        if (!release.available()) {
            return ApiResult.fail(409, "PRODUCT_NOT_RELEASED");
        }
        // A capacity quote is a UX aid, never the invariant.  The ordinary order
        // command is the last server-authoritative boundary before money/order
        // side effects, so it must reject a full account even when a client skips
        // or races the quote endpoint.  Capacity replacement uses its own locked
        // transaction which removes one active device before adding the target.
        if (!"SHARE".equalsIgnoreCase(product.productType())) {
            int activeDevices = Math.max(0, developmentRuntime
                    ? mapper.developmentActiveDeviceCount(userId)
                    : mapper.activeDeviceCount(userId));
            int reservedDevices = Math.max(0, developmentRuntime
                    ? mapper.developmentReservedDeviceOrderCount(userId)
                    : mapper.reservedDeviceOrderCount(userId));
            int slotCap = Math.max(1, mapper.deviceSlotCap());
            if ((long) activeDevices + reservedDevices + qty > slotCap) {
                return ApiResult.fail(409, "CAPACITY_REPLACEMENT_REQUIRED");
            }
        }
        if (inventoryMode == ProductInventoryMode.FINITE
                && (product.stock() == null || product.stock() < qty)) {
            return ApiResult.fail(409, "PRODUCT_OUT_OF_STOCK");
        }
        CanonicalStateMapper.UserEventAttribution attribution = developmentRuntime
                ? mapper.developmentUserEventAttribution(userId)
                : mapper.userEventAttribution(userId);
        if (attribution == null || attribution.accountAgeMonths() == null || !StringUtils.hasText(attribution.cohort())) {
            throw new BizException(409, "USER_EVENT_ATTRIBUTION_UNAVAILABLE");
        }
        String orderNo = "ORD-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        BigDecimal subtotal = product.priceUsdt().multiply(BigDecimal.valueOf(qty)).setScale(6, RoundingMode.DOWN);
        VoucherRedemption voucher = growthLifecyclePublisher.prepareVoucher(
                userId, voucherId, product.productNo(), subtotal);
        BigDecimal discount = voucher.discountUsdt();
        BigDecimal amount = subtotal.subtract(discount).max(BigDecimal.ZERO).setScale(6, RoundingMode.DOWN);
        QuotaReservation quotaReservation = reserveCanonicalPurchaseQuota(userId, product.productNo(), qty);
        if (mapper.decrementProductStock(product.id(), qty) != 1) {
            throw new BizException(409, "PRODUCT_STOCK_CONFLICT");
        }
        if (mapper.insertOrder(userId, orderNo, product.id(), qty, subtotal, discount, amount) != 1) {
            throw new BizException(409, "ORDER_CREATE_CONFLICT");
        }
        if (mapper.insertOrderItem(orderNo, product.id(), qty, subtotal, quotaReservation.reserved(),
                quotaReservation.gateGeneration()) != 1) {
            throw new BizException(409, "ORDER_ITEM_CREATE_CONFLICT");
        }
        growthLifecyclePublisher.redeemVoucher(
                userId, voucher, orderNo, product.productNo(), attribution(attribution));
        outboxService.publishUserEvent(
                "ORDER", orderNo, "checkout.started", userId, normalizePhase(attribution.phase()),
                attribution.accountAgeMonths(), attribution.cohort(), linked(
                "userId", userId,
                "orderId", orderNo,
                "productId", product.id(),
                "quantity", qty,
                "amountUsdt", amount));
        return ApiResult.ok(linked("orderNo", orderNo, "subtotalUsdt", subtotal,
                "discountUsdt", discount, "amountUsdt", amount,
                "voucherId", voucher.voucherId(),
                "voucherRedemption", voucher.applied() ? linked(
                        "voucherId", voucher.voucherId(), "grantId", voucher.grantId(),
                        "status", "REDEEMED", "discountUsdt", voucher.discountUsdt()) : null,
                "paymentStatus", "PENDING", "orderStatus", "PENDING_PAYMENT", "idSource", "server"));
    }

    @Transactional
    public ApiResult<Map<String, Object>> chargeTrial(
            Long userId, Boolean clientChargeSucceeded, BigDecimal clientChargeFailRate, String idempotencyKey) {
        if (mapper.lockUser(userId) == null) return ApiResult.fail(404, "USER_NOT_FOUND");
        return executeOnce("TRIAL_CHARGE", userId, idempotencyKey,
                linked("clientChargeSucceeded", clientChargeSucceeded, "clientChargeFailRate", clientChargeFailRate),
                () -> chargeTrialInternal(userId, clientChargeSucceeded, clientChargeFailRate));
    }

    private ApiResult<Map<String, Object>> chargeTrialInternal(
            Long userId, Boolean clientChargeSucceeded, BigDecimal clientChargeFailRate) {
        if (clientChargeSucceeded != null || clientChargeFailRate != null) {
            return reject(userId, "charge_fail_rate",
                    "客户端提交了扣款结果或失败率，服务器拒绝使用客户端随机结果",
                    "/api/trial/charge", "CLIENT_CHARGE_OUTCOME_REJECTED");
        }
        CanonicalStateMapper.TrialClaim claim = mapper.lockLatestChargeableTrial(userId);
        if (claim == null) return ApiResult.fail(409, "TRIAL_NOT_CHARGEABLE");
        BigDecimal price = claim.priceUsdt() == null ? BigDecimal.ZERO : claim.priceUsdt();
        BigDecimal offset = claim.earnedOffsetUsdt() == null ? BigDecimal.ZERO : claim.earnedOffsetUsdt();
        BigDecimal chargeAmount = price.subtract(offset).max(BigDecimal.ZERO);
        BigDecimal walletBalance = mapper.lockWalletUsdt(userId);
        if (walletBalance == null || walletBalance.compareTo(chargeAmount) < 0) {
            growthLifecyclePublisher.trialChargeAttempted(
                    userId, claim.claimNo(), "FAILED", chargeAmount, "INSUFFICIENT_FUNDS",
                    attribution(mapper.userEventAttribution(userId)));
            return ApiResult.ok(linked("ok", false, "reason", "INSUFFICIENT_FUNDS",
                    "amountUsdt", chargeAmount, "decisionSource", "server"));
        }
        BigDecimal rate = mapper.trialChargeFailRate();
        double boundedRate = rate == null ? 0.01d : Math.max(0d, Math.min(1d, rate.doubleValue()));
        boolean succeeded = RANDOM.nextDouble() >= boundedRate;
        if (!succeeded) {
            growthLifecyclePublisher.trialChargeAttempted(
                    userId, claim.claimNo(), "FAILED", chargeAmount, "SERVER_CHARGE_FAILED",
                    attribution(mapper.userEventAttribution(userId)));
            return ApiResult.ok(linked("ok", false, "reason", "SERVER_CHARGE_FAILED",
                    "amountUsdt", chargeAmount, "decisionSource", "server"));
        }
        if (chargeAmount.signum() > 0 && mapper.debitWalletUsdt(userId, chargeAmount) != 1) {
            throw new BizException(409, "TRIAL_WALLET_CONFLICT");
        }
        BigDecimal balanceAfter = walletBalance.subtract(chargeAmount);
        if (mapper.insertTrialChargeLedger(userId, claim.claimNo(), chargeAmount, balanceAfter) != 1) {
            throw new BizException(409, "TRIAL_LEDGER_CONFLICT");
        }
        String outcome = "CHARGED";
        if (mapper.markTrialChargeAttempt(claim.id(), outcome) != 1) {
            throw new BizException(409, "TRIAL_CHARGE_STATE_CONFLICT");
        }
        growthLifecyclePublisher.trialChargeAttempted(
                userId, claim.claimNo(), "SUCCEEDED", chargeAmount, "CHARGED",
                attribution(mapper.userEventAttribution(userId)));
        return ApiResult.ok(linked("ok", true, "reason", "CHARGED", "amountUsdt", chargeAmount,
                "balanceAfterUsdt", balanceAfter, "decisionSource", "server"));
    }

    private ApiResult<Map<String, Object>> reject(
            Long userId, String tamperPath, String effect, String endpoint, String code) {
        tamperPublisher.publish(userId, tamperPath, effect, endpoint);
        return ApiResult.fail(409, code);
    }

    private String normalizeState(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : fallback;
    }

    private String normalizePhase(String value) {
        String normalized = normalizeState(value, "P1");
        if (normalized.matches("[1-6]")) normalized = "P" + normalized;
        return normalized.matches("P[1-6]") ? normalized : "P1";
    }

    private UserAttribution attribution(CanonicalStateMapper.UserEventAttribution value) {
        if (value == null) return null;
        return new UserAttribution(normalizePhase(value.phase()), value.accountAgeMonths(), value.cohort());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ApiResult<Map<String, Object>> executeOnce(
            String operation, Long userId, String idempotencyKey, Object request,
            java.util.function.Supplier<ApiResult<Map<String, Object>>> action) {
        String scope = "APP:" + operation + ":USER:" + userId;
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotencyService.execute(
                scope, idempotencyKey, sha256(String.valueOf(request)), ApiResult.class, (java.util.function.Supplier) action);
    }

    private ApiResult<Map<String, Object>> executeSandboxOrderOnce(
            Long userId, String idempotencyKey, Object request,
            java.util.function.Supplier<ApiResult<Map<String, Object>>> action) {
        if (!StringUtils.hasText(idempotencyKey) || idempotencyKey.length() > 128) {
            return ApiResult.fail(422, "IDEMPOTENCY_KEY_REQUIRED");
        }
        String runId = commerceAcceptanceRun.requireRunId();
        String requestHash = sha256(String.valueOf(request));
        String key = idempotencyKey.trim();
        // Claim before stock/order mutation. The receipt row is the only
        // cross-request authority. Do not lock an absent receipt first: InnoDB
        // next-key locks would deadlock two first uses of the same key.
        var claim = new CommerceAcceptanceSandboxMapper.OrderReceiptWrite(
                runId, userId, key, requestHash, "{\"state\":\"PENDING\"}");
        boolean claimed = false;
        for (int attempt = 0; attempt < 3; attempt++) {
            var replay = commerceAcceptanceSandboxMapper.findOrderReceipt(runId, userId, key);
            if (replay != null) return replaySandboxReceipt(replay, requestHash);
            try {
                if (commerceAcceptanceSandboxMapper.claimOrderReceipt(claim) == 1) {
                    claimed = true;
                    break;
                }
            } catch (DuplicateKeyException | TransientDataAccessException retryableClaimConflict) {
                // A duplicate/deadlock can only be resolved by the unique receipt:
                // re-read it, then make at most two further claim attempts. No
                // order/inventory action has run at this point.
            }
            // The unique-claim contender may have waited behind a winner. This
            // must be a current locking read, not a repeatable-read snapshot.
            var committed = currentSandboxReceipt(runId, userId, key);
            if (committed != null) return replaySandboxReceipt(committed, requestHash);
        }
        if (!claimed) throw new BizException(409, "COMMERCE_SANDBOX_IDEMPOTENCY_RESULT_UNKNOWN");
        ApiResult<Map<String, Object>> result = action.get();
        if (result.getCode() != 0) {
            commerceAcceptanceSandboxMapper.releaseOrderReceiptClaim(claim);
            return result;
        }
        try {
            String resultJson = JSON.writeValueAsString(result.getData());
            if (commerceAcceptanceSandboxMapper.completeOrderReceipt(new CommerceAcceptanceSandboxMapper.OrderReceiptWrite(
                    runId, userId, key, requestHash, resultJson)) != 1)
                throw new BizException(409, "COMMERCE_SANDBOX_IDEMPOTENCY_RESULT_UNKNOWN");
            return result;
        } catch (BizException known) {
            throw known;
        } catch (Exception serializationFailure) {
            throw new BizException(409, "COMMERCE_SANDBOX_IDEMPOTENCY_RESULT_UNKNOWN");
        }
    }

    private ApiResult<Map<String, Object>> replaySandboxReceipt(
            CommerceAcceptanceSandboxMapper.SandboxOrderReceipt receipt, String requestHash) {
        if (!requestHash.equals(receipt.requestHash())) return ApiResult.fail(409, "IDEMPOTENCY_KEY_PAYLOAD_CONFLICT");
        try {
            return ApiResult.ok(JSON.readValue(receipt.resultJson(), new TypeReference<LinkedHashMap<String, Object>>() { }));
        } catch (Exception malformedReceipt) {
            throw new BizException(409, "COMMERCE_SANDBOX_IDEMPOTENCY_RESULT_UNKNOWN");
        }
    }

    private CommerceAcceptanceSandboxMapper.SandboxOrderReceipt currentSandboxReceipt(
            String runId, Long userId, String key) {
        try {
            return commerceAcceptanceSandboxMapper.lockOrderReceipt(runId, userId, key);
        } catch (TransientDataAccessException retryableCurrentRead) {
            // A deadlock before any domain mutation is safe to retry through the
            // bounded receipt loop; do not manufacture a second checkout action.
            return null;
        }
    }

    private int pendingOrderTtlMinutes() {
        String configured = environment.getProperty("nexion.commerce.pending-order-ttl-minutes");
        if (!StringUtils.hasText(configured)) return 30;
        try {
            return Math.max(1, Integer.parseInt(configured.trim()));
        } catch (NumberFormatException invalidConfiguration) {
            return 30;
        }
    }

    private record QuotaReservation(boolean reserved, Long gateGeneration) {
        static QuotaReservation none() {
            return new QuotaReservation(false, null);
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private Map<String, Object> linked(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }
}
