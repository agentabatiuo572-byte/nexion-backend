package ffdd.opsconsole.device.application;

import ffdd.opsconsole.device.mapper.AppComputeShareEnrollmentMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AppComputeShareEnrollmentService {
    private static final Duration ENROLLMENT_TTL = Duration.ofMinutes(10);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> PRODUCTION_PROFILES = Set.of("prod");
    private static final Set<String> SANDBOX_PROFILES = Set.of("dev", "test");

    private final AppComputeShareEnrollmentMapper mapper;
    private final PlatformConfigFacade config;
    private final AdminIdempotencyService idempotency;
    private final AuditLogService audit;
    private final EventOutboxService outbox;
    private final Environment environment;
    private final Clock clock;

    @Transactional
    public ApiResult<Map<String, Object>> create(Long userId, String requestedGpuModel, String idempotencyKey) {
        if (!productionSurfaceAllowed()) return ApiResult.fail(409, "COMPUTE_SHARE_PRODUCTION_ENROLLMENT_FORBIDDEN");
        if (userId == null || userId <= 0 || mapper.isProductionUser(userId) != 1) {
            return ApiResult.fail(403, "COMPUTE_SHARE_PRODUCTION_USER_REQUIRED");
        }
        if (!featureEnabled()) return ApiResult.fail(409, "COMPUTE_SHARE_DISABLED");
        String gpuModel = normalizeGpuModel(requestedGpuModel);
        if (gpuModel == null) return ApiResult.fail(422, "COMPUTE_SHARE_GPU_MODEL_INVALID");
        String key = normalizeIdempotencyKey(idempotencyKey);
        if (key == null) return ApiResult.fail(422, "IDEMPOTENCY_KEY_REQUIRED");
        return executeOnce(userId, key, gpuModel, () -> createInternal(userId, gpuModel, key));
    }

    private ApiResult<Map<String, Object>> createInternal(Long userId, String gpuModel, String idempotencyKey) {
        if (mapper.lockProductionUser(userId) == null) return ApiResult.fail(403, "COMPUTE_SHARE_PRODUCTION_USER_REQUIRED");
        int cap = Math.max(1, mapper.deviceSlotCap());
        int occupied = Math.max(0, mapper.activeDeviceCount(userId))
                + Math.max(0, mapper.activeEnrollmentCount(userId, clock.instant()));
        if (occupied >= cap) return ApiResult.fail(409, "COMPUTE_SHARE_DEVICE_CAPACITY_REACHED");

        String enrollmentNo = "CSE-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        String pairingCode = "%06d".formatted(RANDOM.nextInt(1_000_000));
        Instant expiresAt = clock.instant().plus(ENROLLMENT_TTL);
        var write = new AppComputeShareEnrollmentMapper.EnrollmentWrite(
                enrollmentNo, userId, gpuModel, hashPairingCode(enrollmentNo, pairingCode), expiresAt);
        if (mapper.insertEnrollment(write) != 1) return ApiResult.fail(409, "COMPUTE_SHARE_ENROLLMENT_CONFLICT");

        Map<String, Object> result = enrollmentReceipt(
                enrollmentNo, pairingCode, "PENDING", gpuModel, expiresAt, null);
        outbox.publish("COMPUTE_SHARE_ENROLLMENT", enrollmentNo, "COMPUTE_SHARE_ENROLLMENT_CREATED",
                Map.of("userId", userId, "enrollmentNo", enrollmentNo, "status", "PENDING"));
        audit.recordRequiredForTrustedActor(AuditLogWriteRequest.builder()
                .action("USER_COMPUTE_SHARE_ENROLLMENT_CREATED").resourceType("COMPUTE_SHARE_ENROLLMENT")
                .resourceId(enrollmentNo).bizNo(enrollmentNo).userId(userId).actorId(userId)
                .actorType("USER").actorUsername("user:" + userId).method("POST")
                .path("/api/app/compute-share/enrollments").result("SUCCESS").riskLevel("MEDIUM")
                .detail(Map.of("idempotencyKey", idempotencyKey, "requestedGpuModel", gpuModel,
                        "status", "PENDING", "expiresAt", expiresAt.toString()))
                .build());
        return ApiResult.ok(result);
    }

    public ApiResult<Map<String, Object>> status(Long userId, String enrollmentNo) {
        if (!productionSurfaceAllowed()) return ApiResult.fail(409, "COMPUTE_SHARE_PRODUCTION_ENROLLMENT_FORBIDDEN");
        if (userId == null || userId <= 0 || mapper.isProductionUser(userId) != 1) {
            return ApiResult.fail(403, "COMPUTE_SHARE_PRODUCTION_USER_REQUIRED");
        }
        String normalizedNo = normalizeEnrollmentNo(enrollmentNo);
        if (normalizedNo == null) return ApiResult.fail(422, "COMPUTE_SHARE_ENROLLMENT_NO_INVALID");
        var row = mapper.findEnrollment(normalizedNo, userId);
        if (row == null) return ApiResult.fail(404, "COMPUTE_SHARE_ENROLLMENT_NOT_FOUND");
        String state = enrollmentState(row);
        return ApiResult.ok(enrollmentReceipt(row.enrollmentNo(), null, state, row.requestedGpuModel(),
                row.expiresAt(), row.deviceId()));
    }

    @Transactional
    public ApiResult<Map<String, Object>> claim(Long userId, String enrollmentNo, String pairingCode,
                                                String instanceNo, String actualGpuModel,
                                                Integer vramTotalGb, Integer basePowerW) {
        if (!productionSurfaceAllowed()) return ApiResult.fail(409, "COMPUTE_SHARE_PRODUCTION_ENROLLMENT_FORBIDDEN");
        if (userId == null || userId <= 0 || mapper.isProductionUser(userId) != 1) {
            return ApiResult.fail(403, "COMPUTE_SHARE_PRODUCTION_USER_REQUIRED");
        }
        String normalizedNo = normalizeEnrollmentNo(enrollmentNo);
        String normalizedCode = pairingCode == null ? "" : pairingCode.trim();
        String normalizedInstance = normalizeInstanceNo(instanceNo);
        String normalizedGpu = normalizeGpuModel(actualGpuModel);
        if (normalizedNo == null || !normalizedCode.matches("^\\d{6}$") || normalizedInstance == null
                || normalizedGpu == null || vramTotalGb == null || vramTotalGb < 1 || vramTotalGb > 128
                || basePowerW == null || basePowerW < 1 || basePowerW > 1500) {
            return ApiResult.fail(422, "COMPUTE_SHARE_PAIRING_REQUEST_INVALID");
        }
        if (mapper.lockProductionUser(userId) == null) return ApiResult.fail(403, "COMPUTE_SHARE_PRODUCTION_USER_REQUIRED");
        var row = mapper.lockEnrollment(normalizedNo);
        if (row == null || !userId.equals(row.userId())) return ApiResult.fail(404, "COMPUTE_SHARE_ENROLLMENT_NOT_FOUND");
        if ("CONNECTED".equalsIgnoreCase(row.status())) {
            return ApiResult.ok(enrollmentReceipt(row.enrollmentNo(), null, "CONNECTED", row.requestedGpuModel(),
                    row.expiresAt(), row.deviceId()));
        }
        if (!"PENDING".equalsIgnoreCase(row.status()) || !clock.instant().isBefore(row.expiresAt())) {
            return ApiResult.fail(409, "COMPUTE_SHARE_ENROLLMENT_EXPIRED");
        }
        if (!normalizedGpu.equalsIgnoreCase(row.requestedGpuModel())) {
            return ApiResult.fail(409, "COMPUTE_SHARE_GPU_MODEL_MISMATCH");
        }
        byte[] expected = hashPairingCode(row.enrollmentNo(), normalizedCode).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = String.valueOf(row.pairingCodeHash()).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, actual)) return ApiResult.fail(409, "COMPUTE_SHARE_PAIRING_CODE_INVALID");
        if (mapper.activeDeviceCount(userId) >= Math.max(1, mapper.deviceSlotCap())) {
            return ApiResult.fail(409, "COMPUTE_SHARE_DEVICE_CAPACITY_REACHED");
        }
        var existing = mapper.findCanonicalDevice(normalizedInstance);
        if (existing != null) {
            return ApiResult.fail(409, "COMPUTE_SHARE_DEVICE_ALREADY_REGISTERED");
        }
        var write = new AppComputeShareEnrollmentMapper.CanonicalDeviceWrite(
                userId, normalizedInstance, "Compute Share · " + normalizedGpu,
                normalizedGpu, vramTotalGb, BigDecimal.valueOf(basePowerW));
        if (mapper.insertCanonicalDevice(write) != 1) return ApiResult.fail(409, "COMPUTE_SHARE_DEVICE_CREATE_CONFLICT");
        var inserted = mapper.findCanonicalDevice(normalizedInstance);
        if (inserted == null || !userId.equals(inserted.userId())) {
            return ApiResult.fail(409, "COMPUTE_SHARE_DEVICE_CREATE_RESULT_UNKNOWN");
        }
        Long deviceId = inserted.id();
        if (mapper.completeEnrollment(row.id(), row.rowVersion(), normalizedInstance, deviceId) != 1) {
            return ApiResult.fail(409, "COMPUTE_SHARE_ENROLLMENT_STATE_CONFLICT");
        }
        Map<String, Object> result = enrollmentReceipt(row.enrollmentNo(), null, "CONNECTED",
                row.requestedGpuModel(), row.expiresAt(), deviceId);
        outbox.publish("COMPUTE_SHARE_ENROLLMENT", row.enrollmentNo(), "COMPUTE_SHARE_DEVICE_CONNECTED",
                Map.of("userId", userId, "enrollmentNo", row.enrollmentNo(), "deviceId", deviceId,
                        "instanceNo", normalizedInstance, "status", "CONNECTED"));
        audit.recordRequiredForTrustedActor(AuditLogWriteRequest.builder()
                .action("USER_COMPUTE_SHARE_DEVICE_CONNECTED").resourceType("USER_DEVICE")
                .resourceId(String.valueOf(deviceId)).bizNo(normalizedInstance).userId(userId).actorId(userId)
                .actorType("USER").actorUsername("user:" + userId).method("POST")
                .path("/api/app/janus/compute-share/enrollments/claim").result("SUCCESS").riskLevel("HIGH")
                .detail(Map.of("enrollmentNo", row.enrollmentNo(), "deviceInstanceNo", normalizedInstance,
                        "gpuModel", normalizedGpu, "status", "CONNECTED"))
                .build());
        return ApiResult.ok(result);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ApiResult<Map<String, Object>> executeOnce(Long userId, String key, String gpuModel,
                                                       Supplier<ApiResult<Map<String, Object>>> action) {
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotency.execute(
                "APP:COMPUTE_SHARE_ENROLLMENT_CREATE:USER:" + userId, key,
                sha256("gpuModel=" + gpuModel), ApiResult.class, (Supplier) action);
    }

    private boolean featureEnabled() {
        return config.activeValue("E.compute.computeShareEnabled")
                .map(value -> Set.of("1", "true", "on", "enabled").contains(value.trim().toLowerCase(Locale.ROOT)))
                .orElse(false);
    }

    private boolean productionSurfaceAllowed() {
        Set<String> profiles = Arrays.stream(environment.getActiveProfiles())
                .map(value -> value.trim().toLowerCase(Locale.ROOT)).filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        if (profiles.isEmpty()) return true;
        if (profiles.stream().anyMatch(SANDBOX_PROFILES::contains)) return false;
        return profiles.size() == 1 && profiles.stream().allMatch(PRODUCTION_PROFILES::contains);
    }

    private String enrollmentState(AppComputeShareEnrollmentMapper.EnrollmentRow row) {
        if ("PENDING".equalsIgnoreCase(row.status()) && !clock.instant().isBefore(row.expiresAt())) return "EXPIRED";
        return String.valueOf(row.status()).toUpperCase(Locale.ROOT);
    }

    private Map<String, Object> enrollmentReceipt(String enrollmentNo, String pairingCode, String status,
                                                  String gpuModel, Instant expiresAt, Long deviceId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enrollmentNo", enrollmentNo);
        result.put("pairingCode", pairingCode);
        result.put("status", status);
        result.put("requestedGpuModel", gpuModel);
        result.put("expiresAt", expiresAt.toString());
        result.put("deviceId", deviceId);
        result.put("source", "server");
        return result;
    }

    private String normalizeGpuModel(String value) {
        String normalized = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        return normalized.length() >= 3 && normalized.length() <= 128 ? normalized : null;
    }

    private String normalizeEnrollmentNo(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return normalized.matches("^CSE-[A-Z0-9]{1,64}$") ? normalized : null;
    }

    private String normalizeInstanceNo(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.matches("^[A-Za-z0-9._:-]{3,64}$") ? normalized : null;
    }

    private String normalizeIdempotencyKey(String value) {
        String normalized = value == null ? "" : value.trim();
        return !normalized.isEmpty() && normalized.length() <= 128 ? normalized : null;
    }

    public static String hashPairingCode(String enrollmentNo, String pairingCode) {
        return sha256(enrollmentNo.trim().toUpperCase(Locale.ROOT) + "\n" + pairingCode.trim());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
