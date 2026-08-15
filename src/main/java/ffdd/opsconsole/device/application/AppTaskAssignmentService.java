package ffdd.opsconsole.device.application;

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
import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper.TaskConfigRow;
import ffdd.opsconsole.shared.api.ApiResult;
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
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AppTaskAssignmentService {
    private static final int LEASE_HOURS = 24;

    private final AppTaskAssignmentMapper mapper;
    private final AdminIdempotencyService idempotencyService;
    private final EventOutboxService outboxService;
    private final AuditLogService auditLogService;
    private final ComputeTaskProofVerifier proofVerifier;
    private final Clock clock;

    @Transactional(readOnly = true)
    public ApiResult<AppTaskAssignmentsResponse> assignments(Long userId) {
        requireUser(userId);
        LocalDateTime now = now();
        String sourceEnvironment = proofVerifier.sourceEnvironment();
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
                "nx_compute_task + nx_compute_receipt + nx_compute_device_task_lock"));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<AppTaskAssignmentView> claim(Long userId, String idempotencyKey, AppTaskClaimRequest request) {
        requireUser(userId);
        Long deviceId = request == null ? null : request.deviceId();
        if (deviceId == null || deviceId <= 0) throw new BizException(422, "TASK_ASSIGNMENT_DEVICE_REQUIRED");
        return executeOnce("APP:E18_TASK_CLAIM:" + proofVerifier.sourceEnvironment() + ":USER:" + userId, idempotencyKey,
                sha256(String.valueOf(deviceId)), () -> claimInternal(userId, deviceId));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<AppTaskAssignmentView> complete(
            Long userId, String taskNo, String idempotencyKey, AppTaskCompleteRequest request) {
        requireUser(userId);
        if (!StringUtils.hasText(taskNo) || !taskNo.trim().matches("[A-Za-z0-9._:-]{1,96}")) {
            throw new BizException(422, "TASK_ASSIGNMENT_TASK_NO_INVALID");
        }
        String normalizedTaskNo = taskNo.trim();
        return executeOnce("APP:E18_TASK_COMPLETE:" + proofVerifier.sourceEnvironment()
                        + ":USER:" + userId + ":TASK:" + normalizedTaskNo,
                idempotencyKey, sha256(normalizedTaskNo + ":" + String.valueOf(request)),
                () -> completeInternal(userId, normalizedTaskNo, request));
    }

    private ApiResult<AppTaskAssignmentView> claimInternal(Long userId, Long deviceId) {
        LocalDateTime now = now();
        String sourceEnvironment = proofVerifier.sourceEnvironment();
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
            if ("PRODUCTION".equals(sourceEnvironment)) mapper.clearRuntimeTask(deviceId, existing.taskNo(), now);
            existing = null;
        }
        if (existing != null) return ApiResult.ok(view(existing));

        TaskConfigRow task = chooseTask(mapper.eligibleTasks(device.vramTotalGb()), device.vramTotalGb());
        BigDecimal reward = midpoint(task.minReward(), task.maxReward());
        int requiredSeconds = requiredSeconds(task.taskClass());
        int taskLockMinutes = taskLockMinutes(device, mapper.taskLockConfig());
        String taskNo = "CTA-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        String completionNonce = UUID.randomUUID().toString().replace("-", "").toLowerCase(Locale.ROOT)
                + UUID.randomUUID().toString().replace("-", "").toLowerCase(Locale.ROOT);
        LocalDateTime leaseExpiresAt = now.plusHours(LEASE_HOURS);
        if (mapper.insertAssignment(taskNo, userId, deviceId, task, reward, requiredSeconds,
                taskLockMinutes, completionNonce, leaseExpiresAt, sourceEnvironment,
                now, leaseExpiresAt) != 1) {
            throw new BizException(409, "TASK_ASSIGNMENT_CREATE_CONFLICT");
        }
        if ("PRODUCTION".equals(sourceEnvironment)) mapper.bindRuntimeTask(deviceId, taskNo, now);
        AppTaskAssignmentView result = new AppTaskAssignmentView(taskNo, deviceId, task.taskId(), task.name(),
                task.taskClass(), task.modelName(), "Nexion App", "RUNNING", reward, requiredSeconds,
                now, now.plusSeconds(requiredSeconds), null, null, completionNonce, leaseExpiresAt);
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action("TASK_ASSIGNMENT_CLAIMED").resourceType("COMPUTE_TASK").resourceId(taskNo)
                .bizNo(taskNo).userId(userId).actorType("USER").actorId(userId)
                .result("SUCCESS").riskLevel("MEDIUM")
                .detail(linked("deviceId", deviceId, "taskId", task.taskId(), "rewardUsdt", reward,
                        "requiredSeconds", requiredSeconds, "taskLockMinutes", taskLockMinutes))
                .build());
        if ("PRODUCTION".equals(proofVerifier.sourceEnvironment())) {
            outboxService.publish("COMPUTE_TASK", taskNo, "TASK_ASSIGNMENT_CLAIMED",
                    linked("userId", userId, "deviceId", deviceId, "taskId", task.taskId()));
        }
        return ApiResult.ok(result);
    }

    private ApiResult<AppTaskAssignmentView> completeInternal(
            Long userId, String taskNo, AppTaskCompleteRequest request) {
        LocalDateTime now = now();
        String sourceEnvironment = proofVerifier.sourceEnvironment();
        AssignmentRow task = mapper.lockAssignment(userId, taskNo, sourceEnvironment);
        if (task == null) throw new BizException(404, "TASK_ASSIGNMENT_NOT_FOUND");
        if (completed(task.status())) throw new BizException(409, "TASK_ASSIGNMENT_PROOF_REPLAYED");
        if (!active(task.status())) throw new BizException(409, "TASK_ASSIGNMENT_STATE_INVALID");
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
        String receiptNo = "CTR-" + taskNo.substring(Math.max(0, taskNo.length() - 32));
        if (mapper.insertReceipt(userId, task.deviceId(), task, receiptNo, proof.proofHash(),
                proof.sandbox() ? "SANDBOX" : "CREDITED",
                proof.sandbox() ? "SANDBOX" : "PRODUCTION", now) != 1) {
            throw new BizException(409, "TASK_ASSIGNMENT_REWARD_CONFLICT");
        }
        if (proof.sandbox()) {
            if (mapper.insertSandboxReward(taskNo, userId, task.deviceId(), receiptNo,
                    task.rewardUsdt(), proof.proofHash(), now) != 1) {
                throw new BizException(409, "TASK_ASSIGNMENT_SANDBOX_LEDGER_CONFLICT");
            }
        } else {
            if (mapper.creditWallet(userId, task.rewardUsdt(), now) != 1) {
                throw new BizException(409, "TASK_ASSIGNMENT_REWARD_CONFLICT");
            }
            BigDecimal balanceAfter = mapper.walletUsdt(userId);
            if (balanceAfter == null
                    || mapper.insertWalletLedger(userId, taskNo, task.rewardUsdt(), balanceAfter, now) != 1
                    || mapper.insertEarningEvent("EARN-" + taskNo, userId, task.deviceId(), receiptNo,
                        task.rewardUsdt(), now) != 1) {
                throw new BizException(409, "TASK_ASSIGNMENT_SETTLEMENT_CONFLICT");
            }
        }
        if (mapper.completeAssignment(userId, taskNo, request.proofNonce(), sourceEnvironment, now) != 1) {
            throw new BizException(409, "TASK_ASSIGNMENT_PROOF_REPLAYED");
        }
        if ("PRODUCTION".equals(sourceEnvironment)) {
            mapper.clearRuntimeTask(task.deviceId(), taskNo, now);
            if (mapper.deactivatePendingDevice(userId, task.deviceId(), now) > 0) {
                mapper.markRuntimeDeactivated(task.deviceId(), now);
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
        }
        return ApiResult.ok(new AppTaskAssignmentView(task.taskNo(), task.deviceId(), task.taskId(),
                task.taskName(), task.taskClass(), task.modelName(), task.clientName(), "COMPLETED",
                task.rewardUsdt(), task.requiredSeconds(), task.startedAt(), completableAt, now, receiptNo,
                null, null));
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
        return safe(tasks).stream()
                .filter(task -> activeDevice(task.status()))
                .filter(task -> !killed(task.killInit()))
                .filter(task -> task.minVram() != null && task.minVram() <= vramTotalGb)
                .filter(task -> midpoint(task.minReward(), task.maxReward()).signum() > 0)
                .max(Comparator.comparing(task -> midpoint(task.minReward(), task.maxReward())
                        .divide(BigDecimal.valueOf(requiredSeconds(task.taskClass())), 12, RoundingMode.HALF_UP)))
                .orElseThrow(() -> new BizException(409, "TASK_ASSIGNMENT_NO_ELIGIBLE_TASK"));
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

    private AppTaskAssignmentView view(AssignmentRow row) {
        LocalDateTime completableAt = row.startedAt() == null || row.requiredSeconds() == null
                ? null : row.startedAt().plusSeconds(row.requiredSeconds());
        return new AppTaskAssignmentView(row.taskNo(), row.deviceId(), row.taskId(), row.taskName(),
                row.taskClass(), row.modelName(), row.clientName(), row.status(), row.rewardUsdt(),
                row.requiredSeconds(), row.startedAt(), completableAt, row.completedAt(), row.receiptNo(),
                active(row.status()) ? row.completionNonce() : null,
                active(row.status()) ? row.proofExpiresAt() : null);
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
