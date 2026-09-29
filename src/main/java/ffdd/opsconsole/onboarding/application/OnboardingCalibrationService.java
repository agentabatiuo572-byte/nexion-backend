package ffdd.opsconsole.onboarding.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.growth.application.WheelSandboxProfile;
import ffdd.opsconsole.onboarding.mapper.OnboardingCalibrationMapper;
import ffdd.opsconsole.onboarding.mapper.OnboardingCalibrationMapper.CalibrationRow;
import ffdd.opsconsole.onboarding.mapper.OnboardingCalibrationMapper.CalibrationWrite;
import ffdd.opsconsole.onboarding.mapper.OnboardingCalibrationMapper.ComparisonRow;
import ffdd.opsconsole.onboarding.mapper.OnboardingCalibrationMapper.DeferredWrite;
import ffdd.opsconsole.onboarding.mapper.OnboardingCalibrationMapper.TierRow;
import ffdd.opsconsole.onboarding.mapper.OnboardingCalibrationMapper.ReplacedPhoneTask;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.security.UserAuthEnvironment;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import java.time.Clock;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only business authority for phone onboarding calibration. The request
 * contains raw observations only; tier, score, TOPS and yield are derived from
 * the server's versioned configuration and persisted under the authenticated
 * user plus device identity.
 */
@Service
@RequiredArgsConstructor
public class OnboardingCalibrationService {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final OnboardingCalibrationMapper mapper;
    private final WheelSandboxProfile wheelSandboxProfile;
    private final Environment environment;
    private final AuditLogService auditLogService;
    private final EventOutboxService outboxService;
    private final PlatformConfigFacade configFacade;
    private final Clock clock;

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> calibrate(Long userId, Request request) {
        if (userId == null || userId <= 0) return ApiResult.fail(403, "USER_AUTH_REQUIRED");
        if (!validRequest(request)) return ApiResult.fail(422, "ONBOARDING_SIGNAL_INVALID");

        Scope scope = scope(userId);
        Integer lockedSandbox = mapper.lockUserSandbox(userId);
        if (lockedSandbox == null || !accountEnvironment().acceptsSandbox(lockedSandbox)) {
            throw new BizException(403, "ONBOARDING_USER_ENVIRONMENT_MISMATCH");
        }
        String deviceId = request.deviceId().trim();
        String hash = requestHash(userId, request, scope);
        CalibrationRow current = scope.sandbox()
                ? mapper.findForUpdateScoped(userId, deviceId, scope.sourceEnvironment(), scope.runId())
                : mapper.findForUpdate(userId, deviceId);
        if (current != null && request.idempotencyKey().trim().equals(current.idempotencyKey())) {
            return hash.equals(current.requestHash())
                    ? ApiResult.ok(project(current))
                    : ApiResult.fail(409, "ONBOARDING_IDEMPOTENCY_CONFLICT");
        }
        if (current != null && !deviceId.equals(current.deviceId())) {
            throw new BizException(409, "PHONE_INSTALLATION_ID_MISMATCH");
        }
        long expected = request.expectedRevision();
        if (current != null && expected != current.rowVersion()) {
            return ApiResult.fail(409, "ONBOARDING_CALIBRATION_REVISION_CONFLICT");
        }

        List<TierRow> tiers = mapper.activeTiers();
        List<ComparisonRow> comparisons = mapper.activeComparisons();
        if (!validConfig(tiers, comparisons)) {
            return ApiResult.fail(503, "ONBOARDING_CALIBRATION_CONFIG_UNAVAILABLE");
        }
        Map<String, Object> derived = derive(request.signals(), tiers);
        long configRevision = Math.max(
                tiers.stream().mapToLong(row -> row.revision() == null ? 0L : row.revision()).max().orElse(0L),
                comparisons.stream().mapToLong(row -> row.revision() == null ? 0L : row.revision()).max().orElse(0L));
        String signalJson = writeJson(request.signals());
        String derivedJson = writeJson(derived);
        String comparisonJson = writeJson(comparisonMaps(comparisons));
        int changed;
        CalibrationWrite write = new CalibrationWrite(userId, deviceId, signalJson, derivedJson,
                comparisonJson, configRevision, request.idempotencyKey().trim(), hash,
                scope.sourceEnvironment(), scope.runId());
        if (current == null) {
            changed = scope.sandbox() ? mapper.insertScoped(write) : mapper.insert(write);
        } else {
            changed = scope.sandbox()
                    ? mapper.updateScoped(userId, deviceId, scope.sourceEnvironment(), scope.runId(), current.rowVersion(),
                            signalJson, derivedJson, comparisonJson, configRevision, request.idempotencyKey().trim(), hash)
                    : mapper.update(userId, deviceId, current.rowVersion(), signalJson, derivedJson,
                            comparisonJson, configRevision, request.idempotencyKey().trim(), hash);
        }
        if (changed != 1) {
            // A row cannot be locked before the very first insert. The mapper's
            // duplicate-key no-op makes two simultaneous first requests wait
            // and converge here instead of surfacing a database exception.
            CalibrationRow winner = scope.sandbox()
                    ? mapper.findScoped(userId, deviceId, scope.sourceEnvironment(), scope.runId())
                    : mapper.find(userId, deviceId);
            if (winner != null && request.idempotencyKey().trim().equals(winner.idempotencyKey())
                    && hash.equals(winner.requestHash())) {
                return ApiResult.ok(project(winner));
            }
            return ApiResult.fail(409, "ONBOARDING_CALIBRATION_REVISION_CONFLICT");
        }
        // Recalibration revokes phone-compute eligibility until a fresh
        // activation command succeeds. Keeping the inventory row active here
        // would let task settlement race ahead of the new binding decision.
        if (current != null) {
            mapper.deactivatePhoneDevice(userId, phoneInstanceNo(userId, scope, deviceId),
                    scope.sourceEnvironment(), scope.runId());
        }
        CalibrationRow saved = scope.sandbox()
                ? mapper.findScoped(userId, deviceId, scope.sourceEnvironment(), scope.runId())
                : mapper.find(userId, deviceId);
        return saved == null
                ? ApiResult.fail(503, "ONBOARDING_CALIBRATION_READBACK_FAILED")
                : ApiResult.ok(project(saved));
    }

    @Transactional(readOnly = true)
    public ApiResult<Map<String, Object>> result(Long userId, String deviceId) {
        if (userId == null || userId <= 0) return ApiResult.fail(403, "USER_AUTH_REQUIRED");
        if (!validDeviceId(deviceId)) return ApiResult.fail(422, "ONBOARDING_DEVICE_INVALID");
        Scope scope = scope(userId);
        CalibrationRow row = scope.sandbox()
                ? mapper.findScoped(userId, deviceId.trim(), scope.sourceEnvironment(), scope.runId())
                : mapper.find(userId, deviceId.trim());
        return row == null ? ApiResult.fail(404, "ONBOARDING_CALIBRATION_NOT_FOUND") : ApiResult.ok(project(row));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> activate(Long userId, ActionRequest request) {
        return transition(userId, request, "ACTIVE", false);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> defer(Long userId, ActionRequest request) {
        return transition(userId, request, "DEFERRED", false);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> deferWithoutProof(Long userId, ActionRequest request) {
        return transition(userId, request, "DEFERRED", true);
    }

    private ApiResult<Map<String, Object>> transition(Long userId, ActionRequest request, String target,
            boolean proofFree) {
        if (userId == null || userId <= 0) return ApiResult.fail(403, "USER_AUTH_REQUIRED");
        if (!validAction(request)) return ApiResult.fail(422, "ONBOARDING_ACTIVATION_REQUEST_INVALID");
        Scope scope = scope(userId);
        Integer lockedSandbox = mapper.lockUserSandbox(userId);
        if (lockedSandbox == null || !accountEnvironment().acceptsSandbox(lockedSandbox)) {
            throw new BizException(403, "ONBOARDING_USER_ENVIRONMENT_MISMATCH");
        }
        String deviceId = request.deviceId().trim();
        String key = request.idempotencyKey().trim();
        String hash = actionHash(userId, request, target, scope);
        CalibrationRow current = scope.sandbox()
                ? mapper.findForUpdateScoped(userId, deviceId, scope.sourceEnvironment(), scope.runId())
                : mapper.findForUpdate(userId, deviceId);
        if (current != null && !deviceId.equals(current.deviceId())) {
            throw new BizException(409, "PHONE_INSTALLATION_ID_MISMATCH");
        }
        // The account lock serializes this decision with calibration. A login
        // session without native proof may only create the first empty defer
        // record or replay that exact request; it cannot change a phone row.
        if (proofFree && current != null) {
            if ("DEFERRED".equals(current.activationStatus())
                    && key.equals(current.activationIdempotencyKey())
                    && hash.equals(current.activationRequestHash())) {
                return ApiResult.ok(project(current));
            }
            return ApiResult.fail(403, "PHONE_NATIVE_SESSION_REQUIRED");
        }
        if (current == null) {
            if (!"DEFERRED".equals(target)) {
                return ApiResult.fail(409, "ONBOARDING_CALIBRATION_REQUIRED");
            }
            if (request.expectedRevision() != 0L) {
                return ApiResult.fail(409, "ONBOARDING_CALIBRATION_REVISION_CONFLICT");
            }
            // A detection request can fail before it creates a calibration
            // row. Persist the user's defer decision as a canonical tombstone
            // instead of treating it as a local-only preference. The empty
            // JSON payloads deliberately contain no invented capability data;
            // a later retry replaces them through the normal revision-0 CAS.
            String placeholderHash = sha256(userId + "|" + scope.sourceEnvironment() + "|" + scope.runId()
                    + "|" + deviceId + "|DEFERRED_WITHOUT_CALIBRATION");
            DeferredWrite deferred = new DeferredWrite(userId, deviceId,
                    "deferred:" + placeholderHash.substring(0, 48), placeholderHash,
                    key, hash, scope.sourceEnvironment(), scope.runId());
            int inserted = mapper.insertDeferred(deferred);
            if (inserted == 1) {
                CalibrationRow saved = scope.sandbox()
                        ? mapper.findScoped(userId, deviceId, scope.sourceEnvironment(), scope.runId())
                        : mapper.find(userId, deviceId);
                return saved == null
                        ? ApiResult.fail(503, "ONBOARDING_ACTIVATION_READBACK_FAILED")
                        : ApiResult.ok(project(saved));
            }
            current = scope.sandbox()
                    ? mapper.findForUpdateScoped(userId, deviceId, scope.sourceEnvironment(), scope.runId())
                    : mapper.findForUpdate(userId, deviceId);
            if (current == null) {
                return ApiResult.fail(503, "ONBOARDING_ACTIVATION_READBACK_FAILED");
            }
        }
        if (key.equals(current.activationIdempotencyKey())) {
            if (!hash.equals(current.activationRequestHash())) {
                return ApiResult.fail(409, "ONBOARDING_ACTIVATION_IDEMPOTENCY_CONFLICT");
            }
            return ApiResult.ok(project(current));
        }
        if (request.expectedRevision() != current.rowVersion()) {
            return ApiResult.fail(409, "ONBOARDING_CALIBRATION_REVISION_CONFLICT");
        }
        Long userDeviceId = current.userDeviceId();
        if ("ACTIVE".equals(target)) {
            validateCurrentCalibration(current);
            validateReplacement(userId, current, scope);
            userDeviceId = bindPhoneDevice(userId, current, scope);
            if (mapper.savePhoneBinding(userId, deviceId, userDeviceId, hardwareKey(current),
                    scope.sourceEnvironment(), scope.runId()) < 1) {
                throw new BizException(503, "ONBOARDING_PHONE_BIND_FAILED");
            }
            if (!scope.sandbox()) mapper.restoreBoundPhoneRuntime(userId, userDeviceId);
        } else {
            // The deterministic instance identity also finds this phone when a
            // legacy calibration has lost its user_device_id link.
            mapper.deactivatePhoneDevice(userId, phoneInstanceNo(userId, scope, deviceId),
                    scope.sourceEnvironment(), scope.runId());
        }
        int changed = scope.sandbox()
                ? mapper.updateActivationScoped(userId, deviceId, scope.sourceEnvironment(), scope.runId(),
                        current.rowVersion(), userDeviceId, target, key, hash)
                : mapper.updateActivation(userId, deviceId, current.rowVersion(), userDeviceId, target, key, hash);
        if (changed != 1) throw new BizException(409, "ONBOARDING_CALIBRATION_REVISION_CONFLICT");
        CalibrationRow saved = scope.sandbox()
                ? mapper.findScoped(userId, deviceId, scope.sourceEnvironment(), scope.runId())
                : mapper.find(userId, deviceId);
        return saved == null
                ? ApiResult.fail(503, "ONBOARDING_ACTIVATION_READBACK_FAILED")
                : ApiResult.ok(project(saved));
    }

    private Long bindPhoneDevice(Long userId, CalibrationRow row, Scope scope) {
        Map<String, Object> canonical = project(row);
        int tier = number(canonical.get("tier")).intValue();
        BigDecimal tops = decimal(canonical.get("tops"));
        BigDecimal dailyUsdt = decimal(canonical.get("baseRateUsdt"));
        BigDecimal dailyNex = decimal(canonical.get("baseRateNex"));
        int memoryGb = signalMemoryGb(canonical.get("signals"));
        String instanceNo = phoneInstanceNo(userId, scope, row.deviceId());
        int changed = mapper.upsertPhoneDevice(userId, instanceNo, "TIER-" + tier,
                "Mobile compute · " + tops.stripTrailingZeros().toPlainString() + " points",
                memoryGb, tops, dailyUsdt, dailyNex, scope.sourceEnvironment(), scope.runId());
        if (changed < 1) throw new BizException(503, "ONBOARDING_PHONE_BIND_FAILED");
        Long userDeviceId = mapper.phoneDeviceId(userId, instanceNo, scope.sourceEnvironment(), scope.runId());
        if (userDeviceId == null || userDeviceId <= 0) {
            throw new BizException(503, "ONBOARDING_PHONE_BIND_READBACK_FAILED");
        }
        if (!scope.sandbox()) cancelReplacedPhoneTasks(userId, userDeviceId);
        mapper.deactivateOtherPhoneDevices(userId, userDeviceId, scope.sourceEnvironment(), scope.runId());
        mapper.deferOtherPhoneCalibrations(userId, userDeviceId, scope.sourceEnvironment(), scope.runId());
        return userDeviceId;
    }

    private PhoneCalibrationPolicy activePolicy() {
        return new PhoneCalibrationConfigService(configFacade, clock).activePolicy();
    }

    private void validateCurrentCalibration(CalibrationRow row) {
        Map<String,Object> result = project(row);
        PhoneCalibrationPolicy policy = activePolicy();
        if (!Boolean.TRUE.equals(result.get("calibrationAvailable")) || !"MATCHED".equals(result.get("calibrationStatus"))) {
            throw new BizException(409, "PHONE_CALIBRATION_PENDING_VERIFICATION");
        }
        if (policy == null || !(result.get("ruleVersion") instanceof Number version) || version.longValue() != policy.version()) {
            throw new BizException(409, "PHONE_CALIBRATION_RULES_CHANGED");
        }
        try {
            Signals signals = JSON.readValue(row.signalJson(), Signals.class);
            var matched = policy.match(hardware(signals));
            if (!"MATCHED".equals(matched.status()) || !matched.ruleId().equals(result.get("ruleId"))
                    || matched.computeValue().compareTo(decimal(result.get("computeValue"))) != 0
                    || !matched.tier().equals(((Number)result.get("tier")).intValue())) {
                throw new BizException(409, "PHONE_CALIBRATION_RULES_CHANGED");
            }
        } catch (JsonProcessingException invalid) {
            throw new BizException(503, "ONBOARDING_CALIBRATION_PAYLOAD_INVALID");
        }
    }

    private void validateReplacement(Long userId, CalibrationRow row, Scope scope) {
        var binding = mapper.phoneBinding(userId, scope.sourceEnvironment(), scope.runId());
        if (binding == null) return;
        if (binding.installationId().equals(row.deviceId())
                && (binding.hardwareKey().isBlank() || binding.hardwareKey().equals(hardwareKey(row)))) return;
        validateReplacementPolicy(binding.changedAt());
    }

    private void validateReplacementPolicy(long changedAt) {
        if (!"on".equals(configFacade.activeValue("E.compute.phoneBinding.allowReplacement").orElse("off"))) {
            throw new BizException(409, "PHONE_REPLACEMENT_DISABLED");
        }
        String days = configFacade.activeValue("E.compute.phoneBinding.minReplacementIntervalDays").orElse("");
        try {
            if (!days.matches("[0-9]+")) throw new NumberFormatException();
            long interval = Math.multiplyExact(Long.parseLong(days), 86400000L);
            if (clock.millis() < Math.addExact(changedAt, interval)) {
                throw new BizException(409, "PHONE_REPLACEMENT_COOLDOWN");
            }
        } catch (NumberFormatException | ArithmeticException invalid) {
            throw new BizException(503, "PHONE_REPLACEMENT_POLICY_UNAVAILABLE");
        }
    }

    /** A proved native login changes execution ownership, never activation or the replacement clock. */
    @Transactional(rollbackFor = Exception.class)
    public Map<String,Object> phoneLogin(Long userId, String deviceId) {
        if (deviceId == null || !deviceId.matches("[A-Za-z0-9._:-]{1,128}")) throw new BizException(422,"ONBOARDING_DEVICE_INVALID");
        Integer sandbox = mapper.lockUserSandbox(userId);
        if (sandbox == null || sandbox != 0) throw new BizException(403,"ONBOARDING_USER_ENVIRONMENT_MISMATCH");
        var binding = mapper.phoneBinding(userId,"PRODUCTION","");
        if (binding == null) return Map.of("status","NEEDS_CALIBRATION");
        mapper.claimPhoneExecution(userId,deviceId);
        if (!deviceId.equals(binding.installationId())) {
            cancelReplacedPhoneTasks(userId,-1L);
            try { validateReplacementPolicy(binding.changedAt()); }
            catch (BizException denied) {
                // Keep the stop committed even when replacement is disabled or still cooling down.
                return Map.of("status",denied.getMessage());
            }
            return Map.of("status","REPLACEMENT_REQUIRED");
        }
        CalibrationRow current = mapper.find(userId,deviceId);
        mapper.restoreBoundPhoneRuntime(userId,binding.userDeviceId());
        return Map.of("status",current != null && "ACTIVE".equals(current.activationStatus()) ? "BOUND" : "NEEDS_CALIBRATION");
    }

    private String hardwareKey(CalibrationRow row) {
        try {
            Signals signals = JSON.readValue(row.signalJson(), Signals.class);
            return sha256(writeJson(List.of(PhoneCalibrationPolicy.normalize(signals.platform()),
                    PhoneCalibrationPolicy.normalize(signals.model()), PhoneCalibrationPolicy.normalize(signals.soc()),
                    PhoneCalibrationPolicy.normalize(signals.gpu()))));
        } catch (JsonProcessingException invalid) {
            throw new BizException(503, "ONBOARDING_CALIBRATION_PAYLOAD_INVALID");
        }
    }

    private String phoneInstanceNo(Long userId, Scope scope, String deviceId) {
        return "PHONE-" + sha256(userId + "|" + scope.sourceEnvironment() + "|"
                + scope.runId() + "|" + deviceId).substring(0, 48);
    }

    private void cancelReplacedPhoneTasks(Long userId, Long keepUserDeviceId) {
        List<ReplacedPhoneTask> tasks = mapper.lockOtherPhoneTasks(userId, keepUserDeviceId);
        if (tasks == null) throw new BizException(503, "ONBOARDING_PHONE_TASK_READ_FAILED");
        for (ReplacedPhoneTask task : tasks) {
            if (mapper.cancelReplacedPhoneTask(userId, task.userDeviceId(), task.taskNo()) != 1) {
                throw new BizException(409, "ONBOARDING_PHONE_TASK_CANCEL_CONFLICT");
            }
            Map<String, Object> fact = Map.of("userId", userId, "oldDeviceId", task.userDeviceId(),
                    "newDeviceId", keepUserDeviceId, "taskNo", task.taskNo(), "status", "CANCELLED",
                    "reason", "PHONE_REPLACED");
            auditLogService.recordRequired(AuditLogWriteRequest.builder()
                    .action("TASK_ASSIGNMENT_PHONE_REPLACED").resourceType("COMPUTE_TASK")
                    .resourceId(task.taskNo()).bizNo(task.taskNo()).userId(userId)
                    .actorType("USER").actorId(userId).result("SUCCESS").riskLevel("MEDIUM")
                    .detail(fact).build());
            outboxService.publish("COMPUTE_TASK", task.taskNo(), "TASK_ASSIGNMENT_PHONE_REPLACED", fact);
        }
        mapper.clearReplacedPhoneRuntime(userId, keepUserDeviceId);
    }

    private int signalMemoryGb(Object value) {
        if (!(value instanceof Map<?, ?> signals)) return 0;
        Object memory = signals.get("memGB");
        if (!(memory instanceof Number number)) return 0;
        double raw = number.doubleValue();
        if (!Double.isFinite(raw) || raw <= 0) return 0;
        return (int) Math.min(128, Math.floor(raw));
    }

    private Number number(Object value) {
        if (value instanceof Number number) return number;
        throw new BizException(503, "ONBOARDING_CALIBRATION_PAYLOAD_INVALID");
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number number) return new BigDecimal(number.toString());
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (RuntimeException invalid) {
            throw new BizException(503, "ONBOARDING_CALIBRATION_PAYLOAD_INVALID");
        }
    }

    Map<String, Object> project(CalibrationRow row) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("userId", row.userId());
        output.put("deviceId", row.deviceId());
        output.put("userDeviceId", row.userDeviceId());
        output.put("serverCanonical", Boolean.TRUE.equals(row.serverCanonical()));
        output.put("source", row.source());
        output.put("sourceEnvironment", row.sourceEnvironment());
        output.put("runId", row.runId());
        output.put("revision", row.rowVersion());
        output.put("configRevision", row.configRevision());
        String activationStatus = row.activationStatus() == null ? "CALIBRATED" : row.activationStatus();
        output.put("activationStatus", activationStatus);
        try {
            Map<String, Object> derived = JSON.readValue(row.derivedJson(), new TypeReference<>() { });
            boolean pending = "PENDING_VERIFICATION".equals(derived.get("calibrationStatus"));
            boolean calibrationAvailable = !derived.isEmpty() && !pending;
            output.put("calibrationAvailable", calibrationAvailable);
            if (calibrationAvailable) {
                output.putAll(derived);
                output.put("signals", JSON.readValue(row.signalJson(), new TypeReference<Map<String, Object>>() { }));
                // The stored JSON is the calibration audit snapshot. Product comparisons are
                // live E1 facts, so an existing calibration must not replay retired yields.
                List<ComparisonRow> currentComparisons = mapper.activeComparisons();
                if (!validComparisons(currentComparisons)) {
                    throw new BizException(503, "ONBOARDING_COMPARISON_UNAVAILABLE");
                }
                output.put("comparisonConfig", comparisonMaps(currentComparisons));
                output.put("configRevision", Math.max(row.configRevision() == null ? 0L : row.configRevision(),
                        currentComparisons.stream().mapToLong(c -> c.revision() == null ? 0L : c.revision())
                                .max().orElse(0L)));
            } else if (pending) {
                output.putAll(derived);
                output.put("calibrationAvailable", false);
                output.put("signals", JSON.readValue(row.signalJson(), new TypeReference<Map<String, Object>>() { }));
                output.put("comparisonConfig", List.of());
            } else {
                if (!"DEFERRED".equals(activationStatus) || row.configRevision() == null
                        || row.configRevision() != 0L) {
                    throw new IllegalStateException("ONBOARDING_CALIBRATION_PAYLOAD_INVALID");
                }
                output.put("score", null);
                output.put("tier", null);
                output.put("tierName", null);
                output.put("tops", null);
                output.put("baseRateUsdt", null);
                output.put("baseRateNex", null);
                output.put("signals", null);
                output.put("comparisonConfig", List.of());
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("ONBOARDING_CALIBRATION_PAYLOAD_INVALID", exception);
        }
        return output;
    }

    String requestHash(Long userId, Request request) {
        return requestHash(userId, request, new Scope("PRODUCTION", ""));
    }

    private String actionHash(Long userId, ActionRequest request, String target, Scope scope) {
        return sha256(userId + "|" + scope.sourceEnvironment() + "|" + scope.runId() + "|"
                + request.deviceId().trim() + "|" + request.expectedRevision() + "|" + target);
    }

    private String sha256(String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private String requestHash(Long userId, Request request, Scope scope) {
        String canonical = userId + "|" + scope.sourceEnvironment() + "|" + scope.runId() + "|"
                + request.deviceId().trim() + "|" + request.expectedRevision() + "|"
                + writeJson(request.signals());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private PhoneCalibrationPolicy.Hardware hardware(Signals signals) {
        return new PhoneCalibrationPolicy.Hardware(signals.platform(), signals.model(), signals.soc(), signals.gpu(),
                signals.memGB() == null ? null : BigDecimal.valueOf(signals.memGB()));
    }

    private Map<String,Object> derive(Signals signals, List<TierRow> tiers) {
        PhoneCalibrationPolicy policy = activePolicy();
        var match = policy == null ? new PhoneCalibrationPolicy.Match("RULES_UNAVAILABLE", null, null, null, 0)
                : policy.match(hardware(signals));
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("calibrationStatus", match.tier() == null ? "PENDING_VERIFICATION" : "MATCHED");
        result.put("pendingReason", match.tier() == null ? match.status() : null);
        result.put("ruleId", match.ruleId());
        result.put("ruleVersion", match.ruleVersion());
        result.put("computeUnit", "platform");
        result.put("computeValue", match.computeValue());
        result.put("score", match.computeValue() == null ? null : match.computeValue().setScale(0, RoundingMode.HALF_UP).intValue());
        result.put("tops", match.computeValue()); // Compatibility transport field; clients display platform points.
        result.put("tier", match.tier());
        TierRow tier = match.tier() == null ? null : tiers.get(match.tier() - 1);
        result.put("tierName", tier == null ? null : tier.name());
        result.put("baseRateUsdt", tier == null ? null : tier.baseRateUsdt());
        result.put("baseRateNex", tier == null ? null : tier.baseRateNex());
        return result;
    }

    private List<Map<String, Object>> comparisonMaps(List<ComparisonRow> rows) {
        List<Map<String, Object>> output = new ArrayList<>();
        for (ComparisonRow row : rows) {
            output.add(Map.of("key", row.configKey(), "label", row.label(), "dailyUsdt", row.dailyUsdt(),
                    "dailyNex", row.dailyNex(), "sortOrder", row.sortOrder()));
        }
        return output;
    }

    private boolean validConfig(List<TierRow> tiers, List<ComparisonRow> comparisons) {
        if (tiers == null || tiers.size() != 5 || !validComparisons(comparisons)) return false;
        for (int i = 0; i < tiers.size(); i++) {
            TierRow row = tiers.get(i);
            if (row == null || row.tier() == null || row.tier() != i + 1 || row.topsMin() == null || row.topsMax() == null
                    || row.topsMin() < 1 || row.topsMax() < row.topsMin() || row.baseRateUsdt() == null
                    || row.baseRateNex() == null || row.baseRateUsdt().signum() <= 0 || row.baseRateNex().signum() <= 0) return false;
            if (i > 0 && (row.baseRateUsdt().compareTo(tiers.get(i - 1).baseRateUsdt()) < 0
                    || row.baseRateNex().compareTo(tiers.get(i - 1).baseRateNex()) < 0)) return false;
        }
        return true;
    }

    private boolean validComparisons(List<ComparisonRow> comparisons) {
        return comparisons != null && !comparisons.isEmpty()
                && comparisons.stream().allMatch(row -> row != null && row.configKey() != null && row.label() != null
                        && row.dailyUsdt() != null && row.dailyNex() != null && row.dailyUsdt().signum() > 0
                        && row.dailyNex().signum() > 0)
                && comparisons.stream().map(ComparisonRow::configKey).toList()
                        .contains("phone");
    }

    private boolean validRequest(Request request) {
        if (request == null || !validDeviceId(request.deviceId()) || request.expectedRevision() < 0
                || request.idempotencyKey() == null || !request.idempotencyKey().trim().matches("[A-Za-z0-9._:-]{8,128}")) return false;
        Signals s = request.signals();
        return s != null && finiteOrNull(s.memGB(), 0, 128) && integerOrNull(s.cores(), 1, 256)
                && finiteOrNull(s.pxDensity(), 1, 10000) && finiteOrNull(s.pingMs(), 0, 5000)
                && integerOrNull(s.batteryLevel(), 0, 100)
                && boundedText(s.model(), 128) && boundedText(s.brand(), 128) && boundedText(s.gpu(), 256)
                && (s.platform() == null || boundedText(s.platform(), 16)) && (s.soc() == null || boundedText(s.soc(), 128));
    }

    private boolean validAction(ActionRequest request) {
        return request != null && validDeviceId(request.deviceId()) && request.expectedRevision() >= 0
                && request.idempotencyKey() != null
                && request.idempotencyKey().trim().matches("[A-Za-z0-9._:-]{8,128}");
    }

    private boolean validDeviceId(String value) {
        return value != null && value.trim().matches("[A-Za-z0-9._:-]{1,128}");
    }

    private boolean boundedText(String value, int max) { return value != null && value.length() <= max; }
    private boolean finite(double value, double min, double max) { return Double.isFinite(value) && value >= min && value <= max; }
    private boolean finiteOrNull(Double value, double min, double max) {
        return value == null || finite(value, min, max);
    }
    private boolean integerOrNull(Integer value, int min, int max) {
        return value == null || (value >= min && value <= max);
    }
    private String writeJson(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("ONBOARDING_JSON_FAILED", exception); }
    }

    private Scope scope(Long userId) {
        WheelSandboxProfile.Mode mode = wheelSandboxProfile == null
                ? WheelSandboxProfile.Mode.PRODUCTION : wheelSandboxProfile.mode();
        if (mode == WheelSandboxProfile.Mode.UNKNOWN) {
            wheelSandboxProfile.requireKnownRuntime();
        }
        Integer accountSandbox = mapper.userSandbox(userId);
        if (accountSandbox == null) {
            throw new BizException(403, "ONBOARDING_USER_ENVIRONMENT_MISMATCH");
        }
        if (!accountEnvironment().acceptsSandbox(accountSandbox)) {
            throw new BizException(403, "ONBOARDING_USER_ENVIRONMENT_MISMATCH");
        }
        if (mode == WheelSandboxProfile.Mode.PRODUCTION) {
            return new Scope("PRODUCTION", "");
        }
        if (mode == WheelSandboxProfile.Mode.SANDBOX) {
            return new Scope("SANDBOX", wheelSandboxProfile.requireRunId());
        }
        throw new IllegalStateException("ONBOARDING_RUNTIME_PROFILE_UNSUPPORTED");
    }

    private UserAuthEnvironment accountEnvironment() {
        return UserAuthEnvironment.resolve(environment)
                .orElseThrow(() -> new BizException(503, "ONBOARDING_RUNTIME_PROFILE_UNSUPPORTED"));
    }

    private record Scope(String sourceEnvironment, String runId) {
        boolean sandbox() { return "SANDBOX".equals(sourceEnvironment); }
    }

    public record Request(String deviceId, long expectedRevision, String idempotencyKey, Signals signals) { }
    public record ActionRequest(String deviceId, long expectedRevision, String idempotencyKey) { }
    public record Signals(Double memGB, Integer cores, String model, String brand, String gpu, Double pxDensity,
                          Double pingMs, Integer batteryLevel, Boolean charging, Boolean networkReachable,
                          String platform, String soc) {
        public Signals(Double memGB, Integer cores, String model, String brand, String gpu, Double pxDensity,
                       Double pingMs, Integer batteryLevel, Boolean charging, Boolean networkReachable) {
            this(memGB, cores, model, brand, gpu, pxDensity, pingMs, batteryLevel, charging, networkReachable, "", "");
        }
    }
}
