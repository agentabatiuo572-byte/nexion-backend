package ffdd.opsconsole.device.application;

import ffdd.opsconsole.device.dto.AppComputeReceiptPage;
import ffdd.opsconsole.device.dto.AppComputeReceiptSummaryView;
import ffdd.opsconsole.device.dto.AppComputeReceiptView;
import ffdd.opsconsole.device.dto.AppTaskAssignmentView;
import ffdd.opsconsole.device.dto.AppTaskAssignmentsResponse;
import ffdd.opsconsole.device.dto.AppTaskClaimRequest;
import ffdd.opsconsole.device.dto.AppTaskCompleteRequest;
import ffdd.opsconsole.device.dto.AppTaskDeviceState;
import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper;
import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper.AssignmentRow;
import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper.ConfigRow;
import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper.DeviceLockRow;
import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper.DeviceRow;
import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper.ReceiptRow;
import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper.TaskConfigRow;
import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.capacity.E3DeviceCapacityPolicy;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AppTaskAssignmentService {
    private static final int LEASE_HOURS = 24;
    private static final String PROVENANCE_SOURCE = "server";
    private static final String PROVENANCE_ENVIRONMENT = "PRODUCTION";
    private static final String PROVENANCE_RUN_ID = "";
    private static final Pattern SANDBOX_RUN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{7,95}");
    private static final Pattern RECEIPT_NO = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,95}");
    private static final Pattern PROOF_HASH = Pattern.compile("[A-Fa-f0-9]{64,128}");
    private static final Set<String> SETTLED_EARNING_STATUSES = Set.of(
            "POSTED", "SUCCESS", "SETTLED", "CREDITED", "PAID");
    private static final String LEGACY_UNSCOPED_RUN_ID = "LEGACY_UNSCOPED";

    private final AppTaskAssignmentMapper mapper;
    private final AdminIdempotencyService idempotencyService;
    private final EventOutboxService outboxService;
    private final AuditLogService auditLogService;
    private final ComputeTaskProofVerifier proofVerifier;
    private final Environment environment;
    private final Clock clock;

    @Transactional(readOnly = true)
    public ApiResult<AppTaskAssignmentsResponse> assignments(Long userId) {
        requireUser(userId);
        if (FundsSandboxProfileGuard.isStrictIsolatedProfile(
                environment == null ? new String[0] : environment.getActiveProfiles())) {
            return acceptanceSandboxAssignments(userId);
        }
        RuntimeScope runtime = requireProductionRuntime(userId);
        LocalDateTime now = now();
        String sourceEnvironment = runtime.sourceEnvironment();
        List<AssignmentRow> rows = safe(mapper.assignments(userId, sourceEnvironment));
        List<AppTaskDeviceState> devices = safe(mapper.ownedDevices(userId)).stream().map(device -> {
            DeviceLockRow lock = mapper.deviceTaskLock(userId, device.id(), sourceEnvironment);
            AppTaskAssignmentView current = rows.stream()
                    .filter(row -> device.id().equals(row.deviceId()) && active(row.status())
                            && (row.leaseExpiresAt() == null || !row.leaseExpiresAt().isBefore(now)))
                    .findFirst().map(this::view).orElse(null);
            List<AppTaskAssignmentView> recent = rows.stream()
                    .filter(row -> device.id().equals(row.deviceId()) && completed(row.status()))
                    .limit(10).map(this::view).toList();
            return new AppTaskDeviceState(device.id(), device.instanceNo(), device.deviceType(),
                    lock == null ? null : lock.lockUntil(), current, recent);
        }).toList();
        return ApiResult.ok(new AppTaskAssignmentsResponse(now, devices,
                PROVENANCE_SOURCE, PROVENANCE_ENVIRONMENT, PROVENANCE_RUN_ID, true));
    }

    private ApiResult<AppTaskAssignmentsResponse> developmentAssignments(Long userId) {
        requireDevelopmentAccount(userId);
        LocalDateTime now = now();
        List<AssignmentRow> rows = safe(mapper.developmentAssignments(userId, PROVENANCE_ENVIRONMENT));
        List<AppTaskDeviceState> devices = safe(mapper.developmentOwnedDevices(userId)).stream().map(device -> {
            DeviceLockRow lock = mapper.developmentDeviceTaskLock(userId, device.id());
            AppTaskAssignmentView current = rows.stream()
                    .filter(row -> device.id().equals(row.deviceId()) && active(row.status())
                            && (row.leaseExpiresAt() == null || !row.leaseExpiresAt().isBefore(now)))
                    .findFirst().map(this::view).orElse(null);
            List<AppTaskAssignmentView> recent = rows.stream()
                    .filter(row -> device.id().equals(row.deviceId()) && completed(row.status()))
                    .limit(10).map(this::view).toList();
            return new AppTaskDeviceState(device.id(), device.instanceNo(), device.deviceType(),
                    lock == null ? null : lock.lockUntil(), current, recent);
        }).toList();
        return ApiResult.ok(new AppTaskAssignmentsResponse(now, devices,
                PROVENANCE_SOURCE, PROVENANCE_ENVIRONMENT, PROVENANCE_RUN_ID, true));
    }

    @Transactional(readOnly = true)
    public ApiResult<AppComputeReceiptView> receipt(Long userId, String receiptNo) {
        requireUser(userId);
        String normalizedReceiptNo = value(receiptNo);
        if (!RECEIPT_NO.matcher(normalizedReceiptNo).matches()) {
            throw new BizException(422, "TASK_RECEIPT_NO_INVALID");
        }
        ReceiptRow row;
        requireProductionRuntime(userId);
        row = mapper.receipt(userId, normalizedReceiptNo);
        if (row == null) throw new BizException(404, "TASK_RECEIPT_NOT_FOUND");
        return ApiResult.ok(receiptView(row));
    }

    @Transactional(readOnly = true)
    public ApiResult<AppComputeReceiptPage> receipts(Long userId, Integer offset, Integer limit) {
        requireUser(userId);
        int normalizedOffset = offset == null ? 0 : offset;
        int normalizedLimit = limit == null ? 20 : limit;
        if (normalizedOffset < 0 || normalizedOffset > 1_000_000
                || normalizedLimit < 1 || normalizedLimit > 50) {
            throw new BizException(422, "TASK_RECEIPT_PAGE_INVALID");
        }
        List<ReceiptRow> rows;
        requireProductionRuntime(userId);
        rows = safe(mapper.receipts(userId, normalizedOffset, normalizedLimit + 1));
        boolean hasMore = rows.size() > normalizedLimit;
        List<AppComputeReceiptSummaryView> items = rows.stream().limit(normalizedLimit)
                .map(this::receiptView)
                .map(view -> new AppComputeReceiptSummaryView(
                        view.receiptNo(), view.taskNo(), view.taskClass(), view.model(), view.client(),
                        view.rewardUsdt(), view.rewardNex(), view.earningStatus(), view.completedAt()))
                .toList();
        Integer nextOffset = hasMore ? normalizedOffset + normalizedLimit : null;
        return ApiResult.ok(new AppComputeReceiptPage(items, nextOffset,
                PROVENANCE_SOURCE, PROVENANCE_ENVIRONMENT, PROVENANCE_RUN_ID, true));
    }

    private ApiResult<AppTaskAssignmentsResponse> acceptanceSandboxAssignments(Long userId) {
        AppTaskAssignmentMapper.UserScope user = mapper.userScope(userId);
        if (user == null || user.sandbox() == null || user.sandbox() != 1) {
            throw new BizException(403, "TASK_ASSIGNMENT_SANDBOX_USER_REQUIRED");
        }
        String runId = sandboxRunId();
        List<AppTaskDeviceState> devices = safe(mapper.sandboxOwnedDevices(userId, runId)).stream()
                .map(device -> new AppTaskDeviceState(device.id(), device.instanceNo(), device.deviceType(),
                        null, null, List.of()))
                .toList();
        // The RunID is an internal development-account isolation key, not App
        // response provenance. Formal UniApp dev and prod builds consume the
        // same Java canonical contract: PRODUCTION plus an empty public RunID.
        return ApiResult.ok(new AppTaskAssignmentsResponse(now(), devices,
                PROVENANCE_SOURCE, PROVENANCE_ENVIRONMENT, PROVENANCE_RUN_ID, true));
    }

    private boolean hasOnlyProfile(String expected) {
        String[] profiles = environment == null ? new String[0] : environment.getActiveProfiles();
        return profiles != null && profiles.length == 1 && expected.equalsIgnoreCase(profiles[0]);
    }

    private String sandboxRunId() {
        String runId = environment == null ? "" : environment.getProperty("nexion.wheel.sandbox.run-id",
                environment.getProperty("NEXION_ACCEPTANCE_RUN_ID", ""));
        runId = value(runId);
        if (!SANDBOX_RUN_ID.matcher(runId).matches() || LEGACY_UNSCOPED_RUN_ID.equalsIgnoreCase(runId)) {
            throw new BizException(503, "TASK_ASSIGNMENT_SANDBOX_RUN_ID_REQUIRED");
        }
        return runId;
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<AppTaskAssignmentView> claim(Long userId, String idempotencyKey, AppTaskClaimRequest request) {
        requireUser(userId);
        RuntimeScope runtime = requireProductionRuntime(userId);
        Long deviceId = request == null ? null : request.deviceId();
        if (deviceId == null || deviceId <= 0) throw new BizException(422, "TASK_ASSIGNMENT_DEVICE_REQUIRED");
        return executeOnce("APP:E18_TASK_CLAIM:" + runtime.sourceEnvironment() + ":USER:" + userId, idempotencyKey,
                sha256(String.valueOf(deviceId)), () -> claimInternal(userId, deviceId, runtime.sourceEnvironment()));
    }

    /** Internal scheduler entrypoint. Device and user ownership are rechecked under lock in claimInternal. */
    @Transactional(rollbackFor = Exception.class)
    public ApiResult<AppTaskAssignmentView> assignAutomatically(Long userId, Long deviceId) {
        requireUser(userId);
        if (deviceId == null || deviceId <= 0) throw new BizException(422, "TASK_ASSIGNMENT_DEVICE_REQUIRED");
        RuntimeScope runtime = requireProductionRuntime(userId);
        return claimInternal(userId, deviceId, runtime.sourceEnvironment());
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<AppTaskAssignmentView> complete(
            Long userId, String taskNo, String idempotencyKey, AppTaskCompleteRequest request) {
        requireUser(userId);
        RuntimeScope runtime = requireProductionRuntime(userId);
        if (!StringUtils.hasText(taskNo) || !taskNo.trim().matches("[A-Za-z0-9._:-]{1,96}")) {
            throw new BizException(422, "TASK_ASSIGNMENT_TASK_NO_INVALID");
        }
        String normalizedTaskNo = taskNo.trim();
        return executeOnce("APP:E18_TASK_COMPLETE:" + runtime.sourceEnvironment()
                        + ":USER:" + userId + ":TASK:" + normalizedTaskNo,
                idempotencyKey, sha256(normalizedTaskNo + ":" + String.valueOf(request)),
                () -> completeInternal(userId, normalizedTaskNo, request, runtime.sourceEnvironment()));
    }

    private ApiResult<AppTaskAssignmentView> claimInternal(Long userId, Long deviceId, String sourceEnvironment) {
        LocalDateTime now = now();
        lockProductionUser(userId);
        DeviceRow device = mapper.lockOwnedDevice(userId, deviceId);
        validateDevice(device);
        DeviceLockRow lock = mapper.lockDeviceTaskLock(userId, deviceId, sourceEnvironment);
        if (lock != null && lock.lockUntil() != null && lock.lockUntil().isAfter(now)) {
            throw new BizException(409, "TASK_ASSIGNMENT_DEVICE_LOCKED_UNTIL:" + lock.lockUntil());
        }
        AssignmentRow existing = mapper.lockActiveAssignment(userId, deviceId, sourceEnvironment);
        if (existing != null && existing.leaseExpiresAt() != null && !existing.leaseExpiresAt().isAfter(now)) {
            if (mapper.expireAssignment(userId, existing.taskNo(), sourceEnvironment, now) != 1) {
                throw new BizException(409, "TASK_ASSIGNMENT_EXPIRY_CONFLICT");
            }
            if ("PRODUCTION".equals(sourceEnvironment)) mapper.clearRuntimeTask(userId, deviceId, existing.taskNo(), now);
            existing = null;
        }
        if (existing != null) return ApiResult.ok(view(existing));

        int routingVram = effectiveRoutingVram(device);
        TaskConfigRow task = chooseTask(mapper.eligibleTasks(routingVram), routingVram);
        List<ConfigRow> capacityRows = safe(mapper.e3CapacityConfig());
        Map<String, String> capacityConfig = capacityConfig(capacityRows);
        if (!E3DeviceCapacityPolicy.validConfig(capacityConfig)) {
            throw new BizException(503, "E3_CAPACITY_CONFIG_INVALID");
        }
        BigDecimal reward;
        try {
            E3DeviceCapacityPolicy.Projection capacity = E3DeviceCapacityPolicy.project(
                    device.productCode(), device.deviceType(), device.purchasedAt(),
                    device.activatedAt(), now, capacityConfig);
            reward = E3DeviceCapacityPolicy.applyCapacity(
                    midpoint(task.minReward(), task.maxReward()), capacity);
        } catch (IllegalArgumentException invalid) {
            throw new BizException(503, invalid.getMessage());
        }
        int requiredSeconds = requiredSeconds(task.taskClass());
        int taskLockMinutes = taskLockMinutes(device, capacityRows.stream()
                .filter(row -> row != null && Set.of("taskLockS1", "taskLockPro", "taskLockRack")
                        .contains(row.configKey()))
                .toList());
        String taskNo = "CTA-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        String completionNonce = UUID.randomUUID().toString().replace("-", "").toLowerCase(Locale.ROOT)
                + UUID.randomUUID().toString().replace("-", "").toLowerCase(Locale.ROOT);
        LocalDateTime leaseExpiresAt = now.plusHours(LEASE_HOURS);
        if (mapper.insertAssignment(taskNo, userId, deviceId, task, reward, requiredSeconds,
                taskLockMinutes, completionNonce, leaseExpiresAt, sourceEnvironment,
                now, leaseExpiresAt) != 1) {
            throw new BizException(409, "TASK_ASSIGNMENT_CREATE_CONFLICT");
        }
        if ("PRODUCTION".equals(sourceEnvironment)) mapper.bindRuntimeTask(deviceId, taskNo, userId, now);
        AppTaskAssignmentView result = new AppTaskAssignmentView(taskNo, deviceId, task.taskId(), task.name(),
                task.taskClass(), task.modelName(), "Nexion App", "RUNNING", reward, requiredSeconds,
                now, now.plusSeconds(requiredSeconds), null, null, completionNonce, leaseExpiresAt,
                PROVENANCE_SOURCE, PROVENANCE_ENVIRONMENT, PROVENANCE_RUN_ID, true);
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action("TASK_ASSIGNMENT_CLAIMED").resourceType("COMPUTE_TASK").resourceId(taskNo)
                .bizNo(taskNo).userId(userId).actorType("USER").actorId(userId)
                .result("SUCCESS").riskLevel("MEDIUM")
                .detail(linked("deviceId", deviceId, "taskId", task.taskId(), "rewardUsdt", reward,
                        "requiredSeconds", requiredSeconds, "taskLockMinutes", taskLockMinutes))
                .build());
        if ("PRODUCTION".equals(sourceEnvironment)) {
            outboxService.publish("COMPUTE_TASK", taskNo, "TASK_ASSIGNMENT_CLAIMED",
                    linked("userId", userId, "deviceId", deviceId, "taskId", task.taskId()));
        }
        return ApiResult.ok(result);
    }

    private ApiResult<AppTaskAssignmentView> completeInternal(
            Long userId, String taskNo, AppTaskCompleteRequest request, String sourceEnvironment) {
        LocalDateTime now = now();
        // Match activation/deactivation and claim: user -> device -> task.
        // The initial unlocked lookup is only for routing; recheck under lock.
        lockProductionUser(userId);
        Long deviceId = mapper.assignmentDeviceId(userId, taskNo, sourceEnvironment);
        if (deviceId == null) throw new BizException(404, "TASK_ASSIGNMENT_NOT_FOUND");
        DeviceRow activeBinding = mapper.lockOwnedDevice(userId, deviceId);
        AssignmentRow task = mapper.lockAssignment(userId, taskNo, sourceEnvironment);
        if (task == null) throw new BizException(404, "TASK_ASSIGNMENT_NOT_FOUND");
        if (!deviceId.equals(task.deviceId())) throw new BizException(409, "TASK_ASSIGNMENT_DEVICE_BINDING_CHANGED");
        if (completed(task.status())) throw new BizException(409, "TASK_ASSIGNMENT_PROOF_REPLAYED");
        if (!active(task.status())) throw new BizException(409, "TASK_ASSIGNMENT_STATE_INVALID");
        validateDevice(activeBinding);
        LocalDateTime completableAt = task.startedAt().plusSeconds(task.requiredSeconds());
        if (now.isBefore(completableAt)) throw new BizException(409, "TASK_ASSIGNMENT_NOT_COMPLETEABLE_UNTIL:" + completableAt);
        if (task.leaseExpiresAt() != null && now.isAfter(task.leaseExpiresAt())) {
            throw new BizException(409, "TASK_ASSIGNMENT_LEASE_EXPIRED");
        }
        AppTaskAssignmentMapper.TaskRuntimeGateRow runtimeGate =
                mapper.taskRuntimeGate(userId, task.deviceId(), task.taskId());
        if (runtimeGate == null || !activeDevice(runtimeGate.status()) || killed(runtimeGate.killInit())) {
            throw new BizException(409, "TASK_ASSIGNMENT_KILLED");
        }
        if (runtimeGate.minVram() == null || runtimeGate.deviceVram() == null
                || runtimeGate.deviceVram() < runtimeGate.minVram()) {
            throw new BizException(409, "TASK_ASSIGNMENT_VRAM_INSUFFICIENT");
        }
        String deviceInstanceNo = mapper.deviceInstanceNo(userId, task.deviceId());
        if (!StringUtils.hasText(deviceInstanceNo)) throw new BizException(409, "TASK_ASSIGNMENT_DEVICE_BINDING_MISSING");
        ComputeTaskProofVerifier.Verification proof = proofVerifier.verify(userId, taskNo, task.deviceId(),
                deviceInstanceNo, task.completionNonce(), task.proofExpiresAt(), request);
        if (proof.sandbox() || !"PRODUCTION".equals(sourceEnvironment)) {
            throw new BizException(503, "TASK_ASSIGNMENT_PROOF_ENVIRONMENT_INVALID");
        }
        String receiptNo = "CTR-" + taskNo.substring(Math.max(0, taskNo.length() - 32));
        if (mapper.insertReceipt(userId, task.deviceId(), task, receiptNo, proof.proofHash(),
                "CREDITED", sourceEnvironment, now) != 1) {
            throw new BizException(409, "TASK_ASSIGNMENT_REWARD_CONFLICT");
        }
        if (mapper.creditWallet(userId, task.deviceId(), task.rewardUsdt(), now) != 1) {
            throw new BizException(409, "TASK_ASSIGNMENT_REWARD_CONFLICT");
        }
        BigDecimal balanceAfter = mapper.walletUsdt(userId);
        if (balanceAfter == null
                || mapper.insertWalletLedger(userId, task.deviceId(), taskNo, task.rewardUsdt(), balanceAfter, now) != 1
                || mapper.insertEarningEvent("EARN-" + taskNo, userId, task.deviceId(), receiptNo,
                    task.rewardUsdt(), now) != 1) {
            throw new BizException(409, "TASK_ASSIGNMENT_SETTLEMENT_CONFLICT");
        }
        if (mapper.completeAssignment(userId, taskNo, request.proofNonce(), sourceEnvironment, now) != 1) {
            throw new BizException(409, "TASK_ASSIGNMENT_PROOF_REPLAYED");
        }
        boolean deferredDeviceDeactivated = false;
        Long deferredDeviceVersion = null;
        if ("PRODUCTION".equals(sourceEnvironment)) {
            mapper.clearRuntimeTask(userId, task.deviceId(), taskNo, now);
            if (mapper.deactivatePendingDevice(userId, task.deviceId(), now) > 0) {
                mapper.markRuntimeDeactivated(userId, task.deviceId(), now);
                deferredDeviceVersion = mapper.deviceRowVersion(userId, task.deviceId());
                if (deferredDeviceVersion == null) {
                    throw new BizException(409, "TASK_ASSIGNMENT_DEFERRED_DEACTIVATION_STATE_UNAVAILABLE");
                }
                deferredDeviceDeactivated = true;
            }
        }
        LocalDateTime lockUntil = now.plusMinutes(Math.max(0, task.taskLockMinutes()));
        mapper.upsertDeviceTaskLock(userId, task.deviceId(), sourceEnvironment, lockUntil, taskNo, now);
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action("TASK_ASSIGNMENT_COMPLETED").resourceType("COMPUTE_TASK").resourceId(taskNo)
                .bizNo(receiptNo).userId(userId).actorType("USER").actorId(userId)
                .result("SUCCESS").riskLevel("HIGH")
                .detail(linked("deviceId", task.deviceId(), "receiptNo", receiptNo,
                        "rewardUsdt", task.rewardUsdt(), "lockUntil", lockUntil,
                        "proofHash", proof.proofHash(), "proofMode", proof.sandbox() ? "SANDBOX" : "PRODUCTION"))
                .build());
        if (!proof.sandbox()) {
            AppTaskAssignmentMapper.UserEventAttribution attribution = mapper.userEventAttribution(userId);
            if (attribution == null) throw new BizException(409, "TASK_ASSIGNMENT_EVENT_ATTRIBUTION_UNAVAILABLE");
            Map<String, Object> payload = linked("task_id", task.taskId(), "task_no", taskNo,
                    "device_id", task.deviceId(), "receipt_no", receiptNo, "amount_usdt", task.rewardUsdt());
            outboxService.publishUserEvent("COMPUTE_TASK", taskNo, "task.completed", userId,
                    attribution.phase(), attribution.accountAgeMonths(), attribution.cohort(), payload);
            outboxService.publishUserEvent("COMPUTE_TASK", taskNo, "earnings.credited", userId,
                    attribution.phase(), attribution.accountAgeMonths(), attribution.cohort(), payload);
            if (deferredDeviceDeactivated) {
                Map<String, Object> deviceState = linked(
                        "userId", userId, "deviceId", task.deviceId(), "instanceNo", activeBinding.instanceNo(),
                        "previousStatus", "ACTIVE", "status", "DEACTIVATED", "rowVersion", deferredDeviceVersion);
                outboxService.publishUserEvent("USER_DEVICE", activeBinding.instanceNo(), "device.deactivated", userId,
                        attribution.phase(), attribution.accountAgeMonths(), attribution.cohort(), deviceState);
                auditLogService.recordRequiredForTrustedActor(AuditLogWriteRequest.builder()
                        .action("USER_DEVICE_DEFERRED_DEACTIVATED").resourceType("USER_DEVICE")
                        .resourceId(String.valueOf(task.deviceId())).bizNo(activeBinding.instanceNo())
                        .userId(userId).actorId(userId).actorType("USER").actorUsername("user:" + userId)
                        .method("POST").path("/api/tasks/" + taskNo + "/complete")
                        .result("SUCCESS").riskLevel("MEDIUM")
                        .detail(linked("trigger", "TASK_SETTLEMENT_COMPLETED", "state", deviceState))
                        .build());
            }
        }
        return ApiResult.ok(new AppTaskAssignmentView(task.taskNo(), task.deviceId(), task.taskId(),
                task.taskName(), task.taskClass(), task.modelName(), task.clientName(), "COMPLETED",
                task.rewardUsdt(), task.requiredSeconds(), task.startedAt(), completableAt, now, receiptNo,
                null, null, PROVENANCE_SOURCE, PROVENANCE_ENVIRONMENT, PROVENANCE_RUN_ID, true));
    }

    private void lockProductionUser(Long userId) {
        if (!userId.equals(mapper.lockProductionUser(userId))) {
            throw new BizException(403, "TASK_ASSIGNMENT_PRODUCTION_USER_REQUIRED");
        }
    }

    private void validateDevice(DeviceRow device) {
        if (device == null) throw new BizException(404, "TASK_ASSIGNMENT_DEVICE_NOT_FOUND");
        if (device.activatedAt() == null || !activeDevice(device.status())) {
            throw new BizException(409, "TASK_ASSIGNMENT_DEVICE_NOT_ACTIVE");
        }
        if (device.vramTotalGb() == null || device.vramTotalGb() < 0) {
            throw new BizException(409, "TASK_ASSIGNMENT_VRAM_UNAVAILABLE");
        }
        if (Boolean.TRUE.equals(device.dispatchPaused())) throw new BizException(409, "TASK_ASSIGNMENT_DC_PAUSED");
        if (StringUtils.hasText(device.pausedReason())) throw new BizException(409, "TASK_ASSIGNMENT_DEVICE_PAUSED");
        if (StringUtils.hasText(device.onlineStatus()) && !"ONLINE".equalsIgnoreCase(device.onlineStatus())) {
            throw new BizException(409, "TASK_ASSIGNMENT_DEVICE_OFFLINE");
        }
    }

    private TaskConfigRow chooseTask(List<TaskConfigRow> tasks, int vramTotalGb) {
        List<TaskConfigRow> pool = safe(tasks);
        if (pool.stream().anyMatch(task -> task == null || task.minVram() == null)) {
            throw new BizException(503, "E2_TASK_CONFIG_INVALID");
        }
        return pool.stream()
                .filter(task -> activeDevice(task.status()))
                .filter(task -> !killed(task.killInit()))
                .filter(task -> task.minVram() != null && task.minVram() <= vramTotalGb)
                .filter(task -> midpoint(task.minReward(), task.maxReward()).signum() > 0)
                .max(Comparator.comparing(task -> midpoint(task.minReward(), task.maxReward())
                        .divide(BigDecimal.valueOf(requiredSeconds(task.taskClass())), 12, RoundingMode.HALF_UP)))
                .orElseThrow(() -> new BizException(409, "TASK_ASSIGNMENT_NO_ELIGIBLE_TASK"));
    }

    private int effectiveRoutingVram(DeviceRow device) {
        String identity = String.join(" ", value(device.deviceType()), value(device.productCode()))
                .toUpperCase(Locale.ROOT);
        if (identity.contains("CLOUD-SHARE") || identity.contains("CLOUD_SHARE")
                || identity.matches(".*\\bSHARE\\b.*")) {
            return 8;
        }
        return device.vramTotalGb();
    }

    public static int taskLockMinutes(DeviceRow device, List<ConfigRow> rows) {
        Map<String, Integer> config = new LinkedHashMap<>();
        for (ConfigRow row : safe(rows)) {
            try {
                int value = Integer.parseInt(row.configValue());
                if (value < 0 || value > 10080) throw new NumberFormatException();
                config.put(row.configKey(), value);
            } catch (RuntimeException ex) {
                throw new BizException(503, "E3_TASK_LOCK_CONFIG_INVALID:" + row.configKey());
            }
        }
        String model = String.join(" ", value(device.deviceType()), value(device.productTier()), value(device.name()))
                .toUpperCase(Locale.ROOT);
        String key = model.contains("RACK") || model.matches(".*\\bP[12]\\b.*") ? "taskLockRack"
                : model.contains("PRO") ? "taskLockPro"
                : model.contains("S1") ? "taskLockS1" : null;
        if (key == null) return 0;
        Integer minutes = config.get(key);
        if (minutes == null) throw new BizException(503, "E3_TASK_LOCK_CONFIG_MISSING:" + key);
        return minutes;
    }

    private Map<String, String> capacityConfig(List<ConfigRow> rows) {
        Map<String, String> config = new LinkedHashMap<>();
        for (ConfigRow row : safe(rows)) {
            if (row == null || !StringUtils.hasText(row.configKey()) || !StringUtils.hasText(row.configValue())
                    || config.put(row.configKey(), row.configValue()) != null) {
                return Map.of();
            }
        }
        return config;
    }

    private AppTaskAssignmentView view(AssignmentRow row) {
        LocalDateTime completableAt = row.startedAt() == null || row.requiredSeconds() == null
                ? null : row.startedAt().plusSeconds(row.requiredSeconds());
        return new AppTaskAssignmentView(row.taskNo(), row.deviceId(),
                StringUtils.hasText(row.taskId()) ? row.taskId() : row.taskNo(), row.taskName(),
                canonicalTaskClass(row.taskClass()), row.modelName(), row.clientName(), row.status(), row.rewardUsdt(),
                row.requiredSeconds(), row.startedAt(), completableAt, row.completedAt(), row.receiptNo(),
                active(row.status()) ? row.completionNonce() : null,
                active(row.status()) ? row.proofExpiresAt() : null,
                PROVENANCE_SOURCE, PROVENANCE_ENVIRONMENT, PROVENANCE_RUN_ID, true);
    }

    private AppComputeReceiptView receiptView(ReceiptRow row) {
        String proofHash = value(row.proofHash()).toLowerCase(Locale.ROOT);
        if (!PROOF_HASH.matcher(proofHash).matches() || row.deviceId() == null || row.deviceId() <= 0
                || row.startedAt() == null || row.completedAt() == null
                || row.completedAt().isBefore(row.startedAt()) || row.durationSec() == null || row.durationSec() < 0
                || !StringUtils.hasText(row.receiptNo()) || !StringUtils.hasText(row.taskNo())
                || !StringUtils.hasText(row.deviceInstanceNo()) || !StringUtils.hasText(row.deviceName())
                || !StringUtils.hasText(row.deviceType()) || !StringUtils.hasText(row.taskName())
                || !StringUtils.hasText(row.clientName()) || !settledEarningStatus(row.earningStatus())
                || row.rewardUsdt() == null || row.rewardUsdt().signum() < 0
                || row.rewardNex() == null || row.rewardNex().signum() < 0) {
            throw new BizException(503, "TASK_RECEIPT_DATA_INVALID");
        }
        return new AppComputeReceiptView(
                row.receiptNo(), row.taskNo(), row.deviceId(), row.deviceInstanceNo(), row.deviceName(),
                row.deviceType(), value(row.deviceGpu()), row.vramTotalGb(),
                StringUtils.hasText(row.taskId()) ? row.taskId() : row.taskNo(), row.taskName(),
                canonicalTaskClass(row.taskClass()), value(row.modelName()), row.clientName(),
                row.rewardUsdt(), row.rewardNex(), row.earningStatus(), proofHash,
                row.startedAt(), row.completedAt(), row.durationSec(),
                PROVENANCE_SOURCE, PROVENANCE_ENVIRONMENT, PROVENANCE_RUN_ID, true);
    }

    private String canonicalTaskClass(String taskClass) {
        return switch (value(taskClass).toUpperCase(Locale.ROOT)) {
            case "IG", "IMAGE_GEN", "IMAGE_GENERATION" -> "IG";
            case "VG", "VIDEO_RENDER", "VIDEO_GENERATION" -> "VG";
            case "LL", "LLM", "LLM_INFERENCE" -> "LL";
            case "FT", "FINE_TUNING", "FINE_TUNE" -> "FT";
            case "EM", "EMBEDDING", "EMBED" -> "EM";
            case "SP", "SPEECH", "SPEECH_PROCESSING" -> "SP";
            default -> throw new BizException(503, "TASK_ASSIGNMENT_CLASS_INVALID");
        };
    }

    private boolean settledEarningStatus(String status) {
        return SETTLED_EARNING_STATUSES.contains(value(status).toUpperCase(Locale.ROOT));
    }

    private int requiredSeconds(String taskClass) {
        return switch (value(taskClass).toUpperCase(Locale.ROOT)) {
            case "IG" -> 18; case "VG" -> 900; case "LL" -> 12;
            case "FT" -> 1800; case "EM" -> 5; case "SP" -> 30; default -> 60;
        };
    }

    private BigDecimal midpoint(BigDecimal min, BigDecimal max) {
        BigDecimal low = min == null ? BigDecimal.ZERO : min;
        BigDecimal high = max == null ? low : max;
        if (low.signum() < 0 || high.compareTo(low) < 0) throw new BizException(503, "E2_TASK_REWARD_INVALID");
        return low.add(high).divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP);
    }

    private boolean killed(String value) {
        String normalized = value(value).toLowerCase(Locale.ROOT).replace(" ", "");
        return normalized.equals("kill") || normalized.equals("killed") || normalized.equals("已kill");
    }

    private boolean active(String status) { return "CLAIMED".equalsIgnoreCase(status) || "RUNNING".equalsIgnoreCase(status); }
    private boolean completed(String status) { return "COMPLETED".equalsIgnoreCase(status); }
    private boolean activeDevice(String status) { return "ACTIVE".equalsIgnoreCase(status) || "ONLINE".equalsIgnoreCase(status); }
    private LocalDateTime now() { return LocalDateTime.now(clock).withNano(0); }
    private void requireUser(Long userId) { if (userId == null || userId <= 0) throw new BizException(401, "USER_AUTH_REQUIRED"); }
    private void requireDevelopmentAccount(Long userId) {
        AppTaskAssignmentMapper.UserScope user = mapper.userScope(userId);
        if (user == null || user.sandbox() == null || user.sandbox() != 1) {
            throw new BizException(403, "TASK_ASSIGNMENT_DEVELOPMENT_USER_REQUIRED");
        }
    }
    private RuntimeScope requireProductionRuntime(Long userId) {
        ProductionDeviceRuntimeGate.requireProduction(environment, "TASK_ASSIGNMENT_RUNTIME_UNSUPPORTED");
        AppTaskAssignmentMapper.UserScope user = mapper.userScope(userId);
        if (user == null || user.sandbox() == null) throw new BizException(403, "TASK_ASSIGNMENT_USER_REQUIRED");
        if (user.sandbox() != 0) throw new BizException(403, "TASK_ASSIGNMENT_PRODUCTION_USER_REQUIRED");
        String sourceEnvironment = value(proofVerifier.sourceEnvironment()).toUpperCase(Locale.ROOT);
        if (!"PRODUCTION".equals(sourceEnvironment)) {
            throw new BizException(503, "TASK_ASSIGNMENT_SOURCE_ENVIRONMENT_INVALID");
        }
        return new RuntimeScope(sourceEnvironment);
    }

    private record RuntimeScope(String sourceEnvironment) { }
    private static String value(String value) { return value == null ? "" : value.trim(); }
    private static <T> List<T> safe(List<T> values) { return values == null ? List.of() : values; }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> ApiResult<T> executeOnce(String scope, String key, String hash, Supplier<ApiResult<T>> action) {
        return (ApiResult<T>) (ApiResult) idempotencyService.execute(scope, key, hash, ApiResult.class, (Supplier) action);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
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
