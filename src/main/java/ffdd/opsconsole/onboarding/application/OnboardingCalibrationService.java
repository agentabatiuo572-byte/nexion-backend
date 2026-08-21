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
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.UserAuthEnvironment;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    private static final int MIN_SCORE = 62;
    private static final int MAX_SCORE = 98;

    private final OnboardingCalibrationMapper mapper;
    private final WheelSandboxProfile wheelSandboxProfile;
    private final Environment environment;

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> calibrate(Long userId, Request request) {
        if (userId == null || userId <= 0) return ApiResult.fail(403, "USER_AUTH_REQUIRED");
        if (!validRequest(request)) return ApiResult.fail(422, "ONBOARDING_SIGNAL_INVALID");

        Scope scope = scope(userId);
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
        long expected = request.expectedRevision();
        if (current != null && expected != current.rowVersion()) {
            return ApiResult.fail(409, "ONBOARDING_CALIBRATION_REVISION_CONFLICT");
        }

        List<TierRow> tiers = mapper.activeTiers();
        List<ComparisonRow> comparisons = mapper.activeComparisons();
        if (!validConfig(tiers, comparisons)) {
            return ApiResult.fail(503, "ONBOARDING_CALIBRATION_CONFIG_UNAVAILABLE");
        }
        Derived derived = derive(request.signals(), tiers);
        long configRevision = Math.max(
                tiers.stream().mapToLong(row -> row.revision() == null ? 0L : row.revision()).max().orElse(0L),
                comparisons.stream().mapToLong(row -> row.revision() == null ? 0L : row.revision()).max().orElse(0L));
        String signalJson = writeJson(request.signals());
        String derivedJson = writeJson(derived.toMap());
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
            mapper.deactivateScopedPhoneDevices(userId, scope.sourceEnvironment(), scope.runId());
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
        return transition(userId, request, "ACTIVE");
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> defer(Long userId, ActionRequest request) {
        return transition(userId, request, "DEFERRED");
    }

    private ApiResult<Map<String, Object>> transition(Long userId, ActionRequest request, String target) {
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
            mapper.deactivateScopedPhoneDevices(userId, scope.sourceEnvironment(), scope.runId());
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
            userDeviceId = bindPhoneDevice(userId, current, scope);
        } else {
            // A deferred calibration is never reward-eligible. Deactivate every
            // onboarding phone in this physical scope, including legacy dirty
            // rows whose calibration lost its user_device_id link.
            mapper.deactivateScopedPhoneDevices(userId, scope.sourceEnvironment(), scope.runId());
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
        String instanceNo = "PHONE-" + sha256(userId + "|" + scope.sourceEnvironment() + "|"
                + scope.runId() + "|" + row.deviceId()).substring(0, 48);
        int changed = mapper.upsertPhoneDevice(userId, instanceNo, "TIER-" + tier,
                "Mobile NPU · ~" + tops.stripTrailingZeros().toPlainString() + " TOPS",
                memoryGb, tops, dailyUsdt, dailyNex, scope.sourceEnvironment(), scope.runId());
        if (changed < 1) throw new BizException(503, "ONBOARDING_PHONE_BIND_FAILED");
        Long userDeviceId = mapper.phoneDeviceId(userId, instanceNo, scope.sourceEnvironment(), scope.runId());
        if (userDeviceId == null || userDeviceId <= 0) {
            throw new BizException(503, "ONBOARDING_PHONE_BIND_READBACK_FAILED");
        }
        mapper.deactivateOtherPhoneDevices(userId, userDeviceId, scope.sourceEnvironment(), scope.runId());
        mapper.deferOtherPhoneCalibrations(userId, userDeviceId, scope.sourceEnvironment(), scope.runId());
        return userDeviceId;
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
            boolean calibrationAvailable = !derived.isEmpty();
            output.put("calibrationAvailable", calibrationAvailable);
            if (calibrationAvailable) {
                output.putAll(derived);
                output.put("signals", JSON.readValue(row.signalJson(), new TypeReference<Map<String, Object>>() { }));
                output.put("comparisonConfig", JSON.readValue(row.comparisonJson(), new TypeReference<List<Map<String, Object>>>() { }));
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

    private Derived derive(Signals signals, List<TierRow> tiers) {
        double model = modelScore(signals.model(), signals.brand());
        // Preserve an unavailable observation as null in the canonical raw
        // signal payload. Only the server chooses the conservative scoring
        // baseline; the client must never turn "unknown" into a fake 0/1.
        double memory = norm(signals.memGB() == null ? 1 : signals.memGB(), 1, 12);
        double cores = norm(signals.cores() == null ? 2 : signals.cores(), 2, 12);
        double pixels = norm(signals.pxDensity() == null ? 600 : signals.pxDensity(), 600, 1400);
        double gpu = gpuScore(signals.gpu());
        int score = (int) Math.round(MIN_SCORE + (0.30 * model + 0.25 * memory + 0.20 * cores
                + 0.15 * gpu + 0.10 * pixels) * (MAX_SCORE - MIN_SCORE));
        score = Math.max(MIN_SCORE, Math.min(MAX_SCORE, score));
        BigDecimal tops = BigDecimal.valueOf(Math.max(8, Math.min(58, 28.3 + 0.99 * (score - 87))))
                .setScale(1, RoundingMode.HALF_UP);
        TierRow tier = tiers.stream().filter(row -> tops.doubleValue() >= row.topsMin()
                && tops.doubleValue() <= row.topsMax()).findFirst().orElse(tiers.get(tiers.size() - 1));
        return new Derived(score, tier.tier(), tier.name(), tops, tier.baseRateUsdt(), tier.baseRateNex());
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
        if (tiers == null || tiers.size() != 5 || comparisons == null || comparisons.isEmpty()) return false;
        for (int i = 0; i < tiers.size(); i++) {
            TierRow row = tiers.get(i);
            if (row == null || row.tier() == null || row.tier() != i + 1 || row.topsMin() == null || row.topsMax() == null
                    || row.topsMin() < 1 || row.topsMax() < row.topsMin() || row.baseRateUsdt() == null
                    || row.baseRateNex() == null || row.baseRateUsdt().signum() <= 0 || row.baseRateNex().signum() <= 0) return false;
            if (i > 0 && (row.baseRateUsdt().compareTo(tiers.get(i - 1).baseRateUsdt()) < 0
                    || row.baseRateNex().compareTo(tiers.get(i - 1).baseRateNex()) < 0)) return false;
        }
        return comparisons.stream().allMatch(row -> row != null && row.configKey() != null && row.label() != null
                && row.dailyUsdt() != null && row.dailyNex() != null && row.dailyUsdt().signum() > 0
                && row.dailyNex().signum() > 0);
    }

    private boolean validRequest(Request request) {
        if (request == null || !validDeviceId(request.deviceId()) || request.expectedRevision() < 0
                || request.idempotencyKey() == null || !request.idempotencyKey().trim().matches("[A-Za-z0-9._:-]{8,128}")) return false;
        Signals s = request.signals();
        return s != null && finiteOrNull(s.memGB(), 0, 128) && integerOrNull(s.cores(), 1, 256)
                && finiteOrNull(s.pxDensity(), 1, 10000) && finiteOrNull(s.pingMs(), 0, 5000)
                && integerOrNull(s.batteryLevel(), 0, 100)
                && boundedText(s.model(), 128) && boundedText(s.brand(), 128) && boundedText(s.gpu(), 256);
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
    private double norm(double value, double min, double max) { return Math.max(0, Math.min(1, (value - min) / (max - min))); }

    private double modelScore(String model, String brand) {
        String value = (brand + " " + model).toLowerCase(Locale.ROOT);
        if (value.matches(".*(iphone\\s*1[5-9]|pro\\s*max|ultra|pixel\\s*[89]|galaxy\\s*s2[2-9]).*")) return .95;
        if (value.matches(".*(iphone\\s*1[2-4]|pixel\\s*[67]|galaxy\\s*s2[01]|oneplus|mi\\s*1[2-4]).*")) return .75;
        if (value.matches(".*(iphone|pixel|galaxy|huawei|honor|oppo|vivo|windows|linux|macintosh|web).*")) return .55;
        return .4;
    }

    private double gpuScore(String gpu) {
        String value = gpu.toLowerCase(Locale.ROOT);
        if (value.isBlank()) return .35;
        if (value.matches(".*(a1[5-9]|a2[0-9]|adreno\\s*7[3-9]|immortalis|rtx|radeon\\s*r[x9]|apple\\s*m[1-9]).*")) return .95;
        if (value.matches(".*(a1[1-4]|adreno\\s*6[4-9]|mali-g7|apple\\s*gpu).*")) return .70;
        if (value.matches(".*(adreno|mali|powervr|apple).*")) return .50;
        return .40;
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
                          Double pingMs, Integer batteryLevel, Boolean charging, Boolean networkReachable) { }
    private record Derived(int score, int tier, String tierName, BigDecimal tops, BigDecimal baseRateUsdt,
                           BigDecimal baseRateNex) {
        Map<String, Object> toMap() { return Map.of("score", score, "tier", tier, "tierName", tierName,
                "tops", tops, "baseRateUsdt", baseRateUsdt, "baseRateNex", baseRateNex); }
    }
}
