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
import ffdd.opsconsole.common.boundary.PhoneReplacementContract;
import ffdd.opsconsole.common.boundary.PhoneActivationCapacityContract;
import org.springframework.security.core.Authentication;
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
public class OnboardingCalibrationService implements ffdd.opsconsole.common.boundary.PhoneExecutionSessionContract {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();
    /**
     * Internal ledger in derived_json: each range key (normalized iPhone model, or "android-tier:" plus the
     * stable tier rule id) to the value this row was ever allocated. The JSON key predates Android tiers.
     */
    private static final String IOS_ALLOCATIONS = "iosAllocations";

    private final OnboardingCalibrationMapper mapper;
    private final WheelSandboxProfile wheelSandboxProfile;
    private final Environment environment;
    private final AuditLogService auditLogService;
    private final EventOutboxService outboxService;
    private final PlatformConfigFacade configFacade;
    private final Clock clock;
    private final PhoneInstallationService installations;
    private final PhoneReplacementService replacements;
    private final PhoneActivationCapacityContract capacity;
    private final ffdd.opsconsole.common.boundary.UserTermsAcceptanceContract terms;

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void requireExecutionOwner(long userId, long userDeviceId) {
        installations.requireSystemExecution(userId, userDeviceId);
    }

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
                    ? ApiResult.ok(appProject(current))
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
        Map<String, String> lock = installModelLock(current);
        Map<String, String> reported = reportedModel(request.signals());
        PhoneModelRisk.Settings risk = new PhoneModelRiskConfig(configFacade, clock).read();
        Map<String, Object> derived = derive(request.signals(), tiers, match -> storedRangeValue(userId, scope, match),
                (policy, match) -> PhoneModelRisk.evaluate(risk, facts(request.signals()),
                        match.matched() && PhoneCalibrationPolicy.IOS_MODEL_RANGE.equals(match.method()) ? policy.iosRule(match.ruleId()) : null,
                        lockChanged(policy, lock, reported), chipChanged(lock, match),
                        risk.accountModelChurn() == PhoneModelRisk.Mode.OFF ? 0
                                : accountModels(userId, scope, policy, reported, risk.churnWindowDays())));
        carryRangeAllocations(current, derived);
        Map<String, String> nextLock = nextLock(lock, reported, derived, risk);
        if (nextLock != null) derived.put(PhoneModelRisk.INSTALL_MODEL_LOCK, nextLock);
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
                return ApiResult.ok(appProject(winner));
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
                : ApiResult.ok(appProject(saved));
    }

    @Transactional(readOnly = true)
    public ApiResult<Map<String, Object>> result(Long userId, String deviceId) {
        if (userId == null || userId <= 0) return ApiResult.fail(403, "USER_AUTH_REQUIRED");
        if (!validDeviceId(deviceId)) return ApiResult.fail(422, "ONBOARDING_DEVICE_INVALID");
        Scope scope = scope(userId);
        CalibrationRow row = scope.sandbox()
                ? mapper.findScoped(userId, deviceId.trim(), scope.sourceEnvironment(), scope.runId())
                : mapper.find(userId, deviceId.trim());
        return row == null ? ApiResult.fail(404, "ONBOARDING_CALIBRATION_NOT_FOUND") : ApiResult.ok(appProject(row));
    }

    /** Read the same prerequisites enforced by activation without changing calibration or binding. */
    List<String> activationBlockReasons(Long userId, String installationId) {
        Scope scope = scope(userId);
        List<String> blocks = new java.util.ArrayList<>();
        var termsBlock = terms.checkTerms(userId);
        if (termsBlock != null) {
            if (termsBlock.getCode() >= 500) throw new BizException(termsBlock.getCode(), termsBlock.getMessage());
            blocks.add(termsBlock.getMessage());
        }
        CalibrationRow row = scope.sandbox()
                ? mapper.findScoped(userId, installationId, scope.sourceEnvironment(), scope.runId())
                : mapper.find(userId, installationId);
        if (row == null) blocks.add("ONBOARDING_CALIBRATION_REQUIRED");
        else {
            try {
                validateCurrentCalibration(row);
            } catch (BizException blocked) {
                if (blocked.getCode() != 409) throw blocked;
                blocks.add(blocked.getMessage());
            }
        }
        return List.copyOf(blocks);
    }

    public ApiResult<Map<String, Object>> bindingStatus(Long userId) {
        if (userId == null || userId <= 0) return ApiResult.fail(403, "USER_AUTH_REQUIRED");
        Scope scope = scope(userId);
        Boolean hasBinding = mapper.hasPhoneBinding(userId, scope.sourceEnvironment(), scope.runId());
        if (hasBinding == null) throw new BizException(503, "PHONE_BINDING_STATUS_UNAVAILABLE");
        return ApiResult.ok(Map.of("serverCanonical", true, "sourceEnvironment", scope.sourceEnvironment(),
                "runId", scope.runId(), "hasBinding", hasBinding));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> activate(Long userId, ActionRequest request) {
        return activate(userId, request, org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication());
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> activate(Long userId, ActionRequest request, Authentication authentication) {
        if (userId == null || userId <= 0) return ApiResult.fail(403, "USER_AUTH_REQUIRED");
        if (!validAction(request) || Boolean.TRUE.equals(request.initialOnly()))
            return ApiResult.fail(422, "ONBOARDING_ACTIVATION_REQUEST_INVALID");
        if (authentication == null || !authentication.isAuthenticated()
                || !String.valueOf(userId).equals(String.valueOf(authentication.getPrincipal())))
            throw new BizException(403, "USER_AUTH_REQUIRED");
        var policy = replacements.lockPolicy();
        Integer sandbox = mapper.lockUserSandbox(userId);
        if (sandbox == null || !accountEnvironment().acceptsSandbox(sandbox))
            throw new BizException(403, "ONBOARDING_USER_ENVIRONMENT_MISMATCH");
        Scope scope = scope(userId);
        String deviceId = request.deviceId().trim();
        var account = new PhoneReplacementContract.Account(userId, scope.sourceEnvironment(), scope.runId());
        var locked = replacements.lockAccount(policy, account, () -> installations.requireCurrent(authentication, deviceId, true));
        var command = replacements.openOperation(locked, new PhoneReplacementContract.Actor("USER", userId),
                "PHONE_ACTIVATE", request.idempotencyKey().trim(), request);
        // An original committed result precedes current calibration, policy, binding and grant checks.
        if (command.replay()) return activationReceipt(replacements.receipt(command.operation()));
        var termsBlock = terms.checkTerms(userId);
        if (termsBlock != null) throw new BizException(termsBlock.getCode(), termsBlock.getMessage());
        CalibrationRow current = scope.sandbox()
                ? mapper.findForUpdateScoped(userId, deviceId, scope.sourceEnvironment(), scope.runId())
                : mapper.findForUpdate(userId, deviceId);
        PhoneReplacementService.ActivationPermit permit;
        try {
            if (current == null) throw new BizException(409, "ONBOARDING_CALIBRATION_REQUIRED");
            if (!deviceId.equals(current.deviceId())) throw new BizException(409, "PHONE_INSTALLATION_ID_MISMATCH");
            if (request.expectedRevision() != current.rowVersion())
                throw new BizException(409, "ONBOARDING_CALIBRATION_REVISION_CONFLICT");
            validateCurrentCalibration(current);
            List<String> hardBlocks = List.of();
            if (locked.binding() != null && PhoneReplacementPolicy.isReplacement(locked.binding().installationId(),
                    locked.binding().hardwareKey(), deviceId, hardwareKey(current))) {
                mapper.lockPhoneDevices(userId);
                if (mapper.phoneReplacementHardPause(userId, locked.binding().userDeviceId()) != null)
                    hardBlocks = List.of("PHONE_REPLACEMENT_RISK_PAUSED");
            }
            permit = replacements.prepareActivation(locked, new PhoneReplacementService.ActivationInput(
                    request.expectedBindingVersion(), request.bindingVersionPresent(), request.expectedPolicyVersion(),
                    Boolean.TRUE.equals(request.replacementConfirmed()), deviceId, hardwareKey(current), request.exceptionGrantId()), hardBlocks);
            capacity.requireCapacity(userId, locked.binding() == null ? null : locked.binding().userDeviceId());
        } catch (BizException rejection) {
            // No device/binding write has occurred. Persist only determinate business rejection receipts.
            if (rejection.getCode() != 409 && rejection.getCode() != 422) throw rejection;
            return ApiResult.fail(rejection.getCode(), rejection.getMessage(),
                    receiptMap(replacements.reject(locked, command, rejection.getMessage())));
        }
        Long device = bindPhoneDevice(userId, current, scope);
        if (mapper.savePhoneBinding(userId, deviceId, device, hardwareKey(current), scope.sourceEnvironment(), scope.runId()) < 1)
            throw new BizException(503, "ONBOARDING_PHONE_BIND_FAILED");
        if (!scope.sandbox()) {
            mapper.restoreBoundPhoneRuntime(userId, device);
            // Only an explicit activation may undo a user stop; login recovery must retain it.
            mapper.resumeUserDeactivatedPhoneRuntime(userId, device);
        }
        String key = request.idempotencyKey().trim();
        String hash = actionHash(userId, request, "ACTIVE", scope);
        int changed = scope.sandbox()
                ? mapper.updateActivationScoped(userId, deviceId, scope.sourceEnvironment(), scope.runId(), current.rowVersion(), device, "ACTIVE", key, hash)
                : mapper.updateActivation(userId, deviceId, current.rowVersion(), device, "ACTIVE", key, hash);
        if (changed != 1) throw new BizException(409, "ONBOARDING_CALIBRATION_REVISION_CONFLICT");
        CalibrationRow saved = scope.sandbox()
                ? mapper.findScoped(userId, deviceId, scope.sourceEnvironment(), scope.runId()) : mapper.find(userId, deviceId);
        var binding = mapper.phoneBinding(userId, scope.sourceEnvironment(), scope.runId());
        if (saved == null || binding == null) throw new BizException(503, "ONBOARDING_ACTIVATION_READBACK_FAILED");
        var result = replacements.recordActivationSuccess(locked, permit, command,
                new PhoneReplacementContract.Binding(binding.installationId(), binding.userDeviceId(), binding.hardwareKey(),
                        binding.changedAt(), binding.version()), project(saved));
        return activationReceipt(result);
    }

    private ApiResult<Map<String,Object>> activationReceipt(PhoneReplacementContract.Receipt receipt) {
        if ("FAILED".equals(receipt.status())) {
            Object reason = receipt.originalResult() instanceof Map<?,?> result ? result.get("conflictType") : null;
            return ApiResult.fail(409, reason == null ? "PHONE_ACTIVATION_FAILED" : String.valueOf(reason), receiptMap(receipt));
        }
        return ApiResult.ok(receiptMap(receipt));
    }

    /** The receipt keeps the full result for audit; the App copy drops the range, also from receipts stored earlier. */
    private Map<String,Object> receiptMap(PhoneReplacementContract.Receipt receipt) {
        Map<String,Object> result = new LinkedHashMap<>();
        Object original = PhoneCalibrationPolicy.appView(receipt.originalResult());
        if (original instanceof Map<?,?> map)
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
        result.put("operationId", receipt.operationId());
        result.put("operationStatus", receipt.status());
        result.put("originalResult", original);
        result.put("currentBinding", receipt.currentResource());
        result.put("auditRef", receipt.auditRef());
        return result;
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
        if (current != null && !deviceId.equals(current.deviceId())) {
            throw new BizException(409, "PHONE_INSTALLATION_ID_MISMATCH");
        }
        if (Boolean.TRUE.equals(request.initialOnly())) {
            if (!"DEFERRED".equals(target)) return ApiResult.fail(422, "ONBOARDING_ACTIVATION_REQUEST_INVALID");
            // A successful retry remains a read of its existing receipt. Every
            // new initial deferral rechecks under the same user lock as binding.
            if (current != null && key.equals(current.activationIdempotencyKey())) {
                return hash.equals(current.activationRequestHash()) ? ApiResult.ok(appProject(current))
                        : ApiResult.fail(409, "ONBOARDING_ACTIVATION_IDEMPOTENCY_CONFLICT");
            }
            if (mapper.phoneBinding(userId, scope.sourceEnvironment(), scope.runId()) != null
                    || current != null && (current.userDeviceId() != null || "ACTIVE".equals(current.activationStatus()))) {
                throw new BizException(409, "PHONE_INITIAL_DEFER_REQUIRES_UNBOUND");
            }
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
                        : ApiResult.ok(appProject(saved));
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
            return ApiResult.ok(appProject(current));
        }
        if (request.expectedRevision() != current.rowVersion()) {
            return ApiResult.fail(409, "ONBOARDING_CALIBRATION_REVISION_CONFLICT");
        }
        Long userDeviceId = current.userDeviceId();
        // The deterministic identity also finds legacy calibrations without a device link.
        mapper.deactivatePhoneDevice(userId, phoneInstanceNo(userId, scope, deviceId),
                scope.sourceEnvironment(), scope.runId());
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
                : ApiResult.ok(appProject(saved));
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
            if (PhoneCalibrationPolicy.IOS_MODEL_RANGE.equals(result.get("calibrationMethod"))
                    || PhoneCalibrationPolicy.ANDROID_SOC_RANGE.equals(result.get("calibrationMethod"))) {
                // Review the stored allocation against the rule; never draw again to compare.
                BigDecimal stored = decimal(result.get("computeValue"));
                if (!matched.matched() || !matched.range() || !matched.ruleId().equals(result.get("ruleId"))
                        || !PhoneCalibrationPolicy.within(matched, stored)
                        || policy.tierFor(stored) != ((Number)result.get("tier")).intValue()) {
                    throw new BizException(409, "PHONE_CALIBRATION_RULES_CHANGED");
                }
                return;
            }
            if (!"MATCHED".equals(matched.status()) || !matched.ruleId().equals(result.get("ruleId"))
                    || matched.computeValue().compareTo(decimal(result.get("computeValue"))) != 0
                    || !matched.tier().equals(((Number)result.get("tier")).intValue())) {
                throw new BizException(409, "PHONE_CALIBRATION_RULES_CHANGED");
            }
        } catch (JsonProcessingException invalid) {
            throw new BizException(503, "ONBOARDING_CALIBRATION_PAYLOAD_INVALID");
        }
    }

    /** The auth transaction already holds user then session locks. Binding is retained on takeover. */
    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void authenticatedApp(long userId, String installationId, boolean executionSupported) {
        Integer sandbox = mapper.lockUserSandbox(userId);
        if (sandbox == null) throw new BizException(403,"USER_AUTH_REQUIRED");
        if (sandbox != 0) return;
        var binding = mapper.phoneBinding(userId,"PRODUCTION","");
        if (binding == null) return;
        mapper.claimPhoneExecution(userId, executionSupported && installationId.equals(binding.installationId())
                ? installationId : "");
        if (executionSupported && installationId.equals(binding.installationId())) {
            mapper.lockPhoneDevices(userId);
            mapper.restoreBoundPhoneRuntime(userId,binding.userDeviceId());
        } else cancelReplacedPhoneTasks(userId,-1L);
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void endedApp(long userId) {
        var binding = mapper.phoneBinding(userId,"PRODUCTION","");
        if (binding == null) return;
        mapper.claimPhoneExecution(userId, "");
        cancelReplacedPhoneTasks(userId,-1L);
    }

    /** Only the guarded current APP may call this; it never claims or stops another installation. */
    @Transactional(rollbackFor = Exception.class)
    public Map<String,Object> phoneLogin(Long userId, String deviceId) {
        if (deviceId == null || !deviceId.matches("[A-Za-z0-9._:-]{1,128}")) throw new BizException(422,"ONBOARDING_DEVICE_INVALID");
        Integer sandbox = mapper.lockUserSandbox(userId);
        if (sandbox == null || sandbox != 0) throw new BizException(403,"ONBOARDING_USER_ENVIRONMENT_MISMATCH");
        var binding = mapper.phoneBinding(userId,"PRODUCTION","");
        if (binding == null) return Map.of("status","NEEDS_CALIBRATION");
        if (!deviceId.equals(binding.installationId())) return Map.of("status","REPLACEMENT_REQUIRED");
        CalibrationRow current = mapper.find(userId,deviceId);
        mapper.lockPhoneDevices(userId);
        mapper.restoreBoundPhoneRuntime(userId,binding.userDeviceId());
        return Map.of("status",current != null && "ACTIVE".equals(current.activationStatus()) ? "BOUND" : "NEEDS_CALIBRATION");
    }

    String hardwareKey(CalibrationRow row) {
        try {
            Signals signals = JSON.readValue(row.signalJson(), Signals.class);
            Map<String, Object> derived = JSON.readValue(row.derivedJson(), new TypeReference<>() { });
            // An Android phone is its model and stable chip: a chip moved between tiers, renamed or
            // reported under another alias stays the same phone. Tier, rule version and value never count.
            if (PhoneCalibrationPolicy.ANDROID_SOC_RANGE.equals(derived.get("calibrationMethod"))) {
                return sha256(writeJson(List.of("android", PhoneCalibrationPolicy.normalize(signals.model()),
                        String.valueOf(derived.get(PhoneCalibrationPolicy.SOC_KEY)))));
            }
            // An iPhone is identified by its canonical model, whether the SDK reported the name or an identifier.
            Object model = PhoneCalibrationPolicy.IOS_MODEL_RANGE.equals(derived.get("calibrationMethod"))
                    ? derived.get("model") : signals.model();
            return sha256(writeJson(List.of(PhoneCalibrationPolicy.normalize(signals.platform()),
                    PhoneCalibrationPolicy.normalize((String) model), PhoneCalibrationPolicy.normalize(signals.soc()),
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
        mapper.lockPhoneDevices(userId);
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

    /** What the App receives: the canonical result without the operator range. */
    private Map<String, Object> appProject(CalibrationRow row) {
        return PhoneCalibrationPolicy.appView(project(row));
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
            derived.remove(IOS_ALLOCATIONS);
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
                + request.deviceId().trim() + "|" + request.expectedRevision() + "|" + target
                + (Boolean.TRUE.equals(request.initialOnly()) ? "|INITIAL_ONLY" : ""));
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
        return new PhoneCalibrationPolicy.Hardware(signals.platform(), signals.model(), signals.socIds());
    }

    /**
     * The account's earlier allocation for the same range (iPhone model or Android tier), if it is still
     * inside the matched range. A retry with a new idempotency key, a reinstall, an unrelated rule publish
     * or a detour through another report therefore reads the stored value instead of drawing again.
     */
    private BigDecimal storedRangeValue(Long userId, Scope scope, PhoneCalibrationPolicy.Match match) {
        String key = allocationKey(match.method(), match.model(), match.ruleId());
        for (String json : mapper.iosRangeAllocations(userId, scope.sourceEnvironment(), scope.runId())) {
            try {
                Object stored = rangeAllocations(JSON.readValue(json, new TypeReference<>() { })).get(key);
                if (stored == null) continue;
                BigDecimal value = decimal(stored);
                if (PhoneCalibrationPolicy.within(match, value)) return value;
            } catch (JsonProcessingException | BizException unreadable) {
                // An unreadable snapshot cannot be reused; the caller allocates a fresh value.
            }
        }
        return null;
    }

    /** iPhones share a value per canonical model; Android phones per tier rule, whatever chip of the tier. */
    private static String allocationKey(Object method, Object model, Object ruleId) {
        return PhoneCalibrationPolicy.ANDROID_SOC_RANGE.equals(method) ? "android-tier:" + ruleId
                : PhoneCalibrationPolicy.normalize(String.valueOf(model));
    }

    /**
     * The installation's locked model: the stored lock, none when support released it, else (a row
     * calibrated before locks were stored) the model its latest report carried.
     */
    private static Map<String, String> installModelLock(CalibrationRow row) {
        if (row == null) return null;
        try {
            Map<String, Object> derived = JSON.readValue(row.derivedJson(), new TypeReference<>() { });
            if (derived.get(PhoneModelRisk.INSTALL_MODEL_LOCK) instanceof Map<?, ?> lock) {
                if (Boolean.TRUE.equals(lock.get("released"))) return null;
                if (lock.get("model") instanceof String model && !model.isBlank()) {
                    Map<String, String> stored = lockOf(lock.get("platform") instanceof String platform ? platform : "", model);
                    if (lock.get(PhoneCalibrationPolicy.SOC_KEY) instanceof String socKey) stored.put(PhoneCalibrationPolicy.SOC_KEY, socKey);
                    return stored;
                }
            }
            Map<String, Object> signals = JSON.readValue(row.signalJson(), new TypeReference<>() { });
            return signals.get("model") instanceof String model && !model.isBlank()
                    ? lockOf(signals.get("platform") instanceof String platform ? platform : "", model) : null;
        } catch (JsonProcessingException unreadable) {
            return null;
        }
    }

    private static Map<String, String> reportedModel(Signals signals) {
        return signals.model() == null || signals.model().isBlank() ? null : lockOf(signals.platform(), signals.model());
    }

    private static Map<String, String> lockOf(String platform, String model) {
        Map<String, String> lock = new LinkedHashMap<>();
        lock.put("platform", PhoneCalibrationPolicy.normalize(platform));
        lock.put("model", model.trim());
        return lock;
    }

    /**
     * The installation's chip lock is the first chip it was identified as; a report that resolves to
     * another chip changed phones. Unknown reports set no chip, so a later identification is not a change.
     */
    private static boolean chipChanged(Map<String, String> lock, PhoneCalibrationPolicy.Match match) {
        return lock != null && lock.get(PhoneCalibrationPolicy.SOC_KEY) != null && match.matched() && match.socKey() != null
                && !lock.get(PhoneCalibrationPolicy.SOC_KEY).equals(match.socKey());
    }

    /**
     * Enforced, the first model (and, once identified, chip) an installation reports stays its lock until
     * support releases it. Observed or off, the lock follows the report, so enforcing later starts from each
     * current phone instead of holding everyone who legitimately moved phones meanwhile.
     */
    private static Map<String, String> nextLock(Map<String, String> lock, Map<String, String> reported,
                                                Map<String, Object> derived, PhoneModelRisk.Settings risk) {
        String socKey = derived.get(PhoneCalibrationPolicy.SOC_KEY) instanceof String key ? key : null;
        if (lock != null && risk.installModelLock() == PhoneModelRisk.Mode.ENFORCE) {
            Map<String, String> kept = new LinkedHashMap<>(lock);
            if (kept.get(PhoneCalibrationPolicy.SOC_KEY) == null && socKey != null) kept.put(PhoneCalibrationPolicy.SOC_KEY, socKey);
            return kept;
        }
        if (reported == null) return lock;
        Map<String, String> next = new LinkedHashMap<>(reported);
        if (socKey != null) next.put(PhoneCalibrationPolicy.SOC_KEY, socKey);
        return next;
    }

    /** A real phone never reports another model; name and identifier of one iPhone compare equal. */
    private static boolean lockChanged(PhoneCalibrationPolicy policy, Map<String, String> lock, Map<String, String> reported) {
        if (lock == null || reported == null) return false;
        // A side without a platform (older rows or builds) compares under the other side's platform.
        String lockPlatform = lock.get("platform").isEmpty() ? reported.get("platform") : lock.get("platform");
        String reportPlatform = reported.get("platform").isEmpty() ? lockPlatform : reported.get("platform");
        return !java.util.Objects.equals(policy.modelIdentity(lockPlatform, lock.get("model")),
                policy.modelIdentity(reportPlatform, reported.get("model")));
    }

    /** Distinct models the account reported within the window, this report included. */
    private int accountModels(Long userId, Scope scope, PhoneCalibrationPolicy policy, Map<String, String> reported, int days) {
        java.util.Set<String> models = new java.util.HashSet<>();
        if (reported != null) models.add(policy.modelIdentity(reported.get("platform"), reported.get("model")));
        for (var row : mapper.accountModelReports(userId, scope.sourceEnvironment(), scope.runId(), days)) {
            if (row == null) continue; // MyBatis maps a row whose columns are all NULL (an empty report) to null.
            // Reports written before platforms were sent carry none: count them under this report's platform.
            String platform = row.platform() == null || row.platform().isEmpty()
                    ? (reported == null ? "" : reported.get("platform")) : row.platform();
            if (row.model() != null && !row.model().isBlank()) models.add(policy.modelIdentity(platform, row.model()));
            if (row.lockModel() != null && !row.lockModel().isBlank()) {
                models.add(policy.modelIdentity(row.lockPlatform() == null || row.lockPlatform().isEmpty() ? platform
                        : row.lockPlatform(), row.lockModel()));
            }
        }
        models.remove(null);
        return models.size();
    }

    private static PhoneModelRisk.Facts facts(Signals signals) {
        // An implausible scale only skips the scale check; it never rejects the calibration.
        Double ratio = signals.pixelRatio() != null && Double.isFinite(signals.pixelRatio())
                && signals.pixelRatio() >= 1 && signals.pixelRatio() <= 10 ? signals.pixelRatio() : null;
        return new PhoneModelRisk.Facts(signals.platform(), signals.osVersion(), ratio, signals.screenWidth(), signals.screenHeight());
    }

    /** Keeps every allocation the row has held, newest first, when a recalibration overwrites its snapshot. */
    private void carryRangeAllocations(CalibrationRow previous, Map<String, Object> derived) {
        Map<String, Object> ledger = new LinkedHashMap<>(rangeAllocations(derived));
        if (previous != null) {
            try {
                rangeAllocations(JSON.readValue(previous.derivedJson(), new TypeReference<>() { })).forEach(ledger::putIfAbsent);
            } catch (JsonProcessingException unreadable) {
                // Nothing reusable survives an unreadable snapshot.
            }
        }
        if (!ledger.isEmpty()) derived.put(IOS_ALLOCATIONS, ledger);
    }

    /** The snapshot's own range allocation followed by its ledger; rows written before the ledger have only the former. */
    private static Map<String, Object> rangeAllocations(Map<String, Object> snapshot) {
        Map<String, Object> out = new LinkedHashMap<>();
        Object method = snapshot.get("calibrationMethod");
        if ((PhoneCalibrationPolicy.IOS_MODEL_RANGE.equals(method) || PhoneCalibrationPolicy.ANDROID_SOC_RANGE.equals(method))
                && "MATCHED".equals(snapshot.get("calibrationStatus")) && snapshot.get("computeValue") != null) {
            out.put(allocationKey(method, snapshot.get("model"), snapshot.get("ruleId")), snapshot.get("computeValue"));
        }
        if (snapshot.get(IOS_ALLOCATIONS) instanceof Map<?, ?> ledger) {
            ledger.forEach((model, value) -> { if (value != null) out.putIfAbsent(String.valueOf(model), value); });
        }
        return out;
    }

    private Map<String,Object> derive(Signals signals, List<TierRow> tiers,
                                      java.util.function.Function<PhoneCalibrationPolicy.Match, BigDecimal> storedRangeValue,
                                      java.util.function.BiFunction<PhoneCalibrationPolicy, PhoneCalibrationPolicy.Match,
                                              PhoneModelRisk.Outcome> riskCheck) {
        PhoneCalibrationPolicy policy = activePolicy();
        var match = policy == null ? new PhoneCalibrationPolicy.Match("RULES_UNAVAILABLE", null, null, null, 0)
                : policy.match(hardware(signals));
        // Checks run before any draw: a held calibration allocates nothing, so probing cannot reveal a range.
        PhoneModelRisk.Outcome risk = policy == null ? null : riskCheck.apply(policy, match);
        if (risk != null && risk.pendingReason() != null) {
            match = new PhoneCalibrationPolicy.Match(risk.pendingReason(), null, null, null, policy.version());
        }
        BigDecimal value = match.computeValue();
        Integer matchedTier = match.tier();
        if (match.matched() && match.range()) {
            BigDecimal stored = storedRangeValue.apply(match);
            value = stored != null ? stored : PhoneCalibrationPolicy.draw(match, RANDOM);
            matchedTier = policy.tierFor(value);
        }
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("calibrationStatus", matchedTier == null ? "PENDING_VERIFICATION" : "MATCHED");
        result.put("pendingReason", matchedTier == null ? match.status() : null);
        result.put("ruleId", match.ruleId());
        result.put("ruleVersion", match.ruleVersion());
        result.put("computeUnit", "platform");
        result.put("computeValue", value);
        result.put("score", value == null ? null : value.setScale(0, RoundingMode.HALF_UP).intValue());
        result.put("tops", value); // Compatibility transport field; clients display platform points.
        result.put("tier", matchedTier);
        if (match.range()) {
            // Snapshot of the rule the value was drawn from, kept even after the rule changes or is deleted.
            result.put("calibrationMethod", match.method());
            if (PhoneCalibrationPolicy.ANDROID_SOC_RANGE.equals(match.method())) {
                result.put(PhoneCalibrationPolicy.SOC, match.model());
                result.put(PhoneCalibrationPolicy.SOC_KEY, match.socKey());
            } else {
                result.put("model", match.model());
            }
            result.put(PhoneCalibrationPolicy.MIN_COMPUTE_VALUE, match.minComputeValue());
            result.put(PhoneCalibrationPolicy.MAX_COMPUTE_VALUE, match.maxComputeValue());
            result.put(PhoneCalibrationPolicy.VALUE_SOURCE, PhoneCalibrationPolicy.OPERATOR_PRESET);
        }
        if (risk != null && !risk.findings().isEmpty()) result.put(PhoneModelRisk.RISK_FLAGS, risk.flags());
        TierRow tier = matchedTier == null ? null : tiers.get(matchedTier - 1);
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
                && (s.platform() == null || boundedText(s.platform(), 16)) && (s.soc() == null || boundedText(s.soc(), 128))
                && (s.osVersion() == null || boundedText(s.osVersion(), 32))
                && integerOrNull(s.screenWidth(), 1, 10000) && integerOrNull(s.screenHeight(), 1, 10000)
                && (s.socIds() == null || s.socIds().size() <= 4 && s.socIds().stream().allMatch(id -> id != null && boundedText(id, 128)));
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
    public record ActionRequest(String deviceId, long expectedRevision, String idempotencyKey, Boolean initialOnly,
                                Long expectedBindingVersion, Long expectedPolicyVersion, String exceptionGrantId,
                                Boolean replacementConfirmed, boolean bindingVersionPresent) {
        public ActionRequest(String deviceId, long expectedRevision, String idempotencyKey, Boolean initialOnly) {
            this(deviceId, expectedRevision, idempotencyKey, initialOnly, null, null, null, null, false);
        }
        public ActionRequest(String deviceId, long expectedRevision, String idempotencyKey) {
            this(deviceId, expectedRevision, idempotencyKey, null);
        }
        @com.fasterxml.jackson.annotation.JsonCreator(mode = com.fasterxml.jackson.annotation.JsonCreator.Mode.DELEGATING)
        public static ActionRequest fromJson(com.fasterxml.jackson.databind.JsonNode node) {
            Long revision = integer(node, "expectedRevision");
            return new ActionRequest(text(node, "deviceId"), revision == null ? 0 : revision,
                    text(node, "idempotencyKey"), flag(node, "initialOnly"), integer(node, "expectedBindingVersion"),
                    integer(node, "expectedPolicyVersion"), text(node, "exceptionGrantId"), flag(node, "replacementConfirmed"),
                    node.has("expectedBindingVersion"));
        }
        private static String text(com.fasterxml.jackson.databind.JsonNode node, String name) {
            var value = node.get(name);
            if (value == null || value.isNull()) return null;
            if (!value.isTextual()) throw new IllegalArgumentException(name + " must be a string");
            return value.textValue();
        }
        private static Long integer(com.fasterxml.jackson.databind.JsonNode node, String name) {
            var value = node.get(name);
            if (value == null || value.isNull()) return null;
            if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0)
                throw new IllegalArgumentException(name + " must be a nonnegative integer");
            return value.longValue();
        }
        private static Boolean flag(com.fasterxml.jackson.databind.JsonNode node, String name) {
            var value = node.get(name);
            if (value == null || value.isNull()) return null;
            if (!value.isBoolean()) throw new IllegalArgumentException(name + " must be a boolean");
            return value.booleanValue();
        }
    }
    /**
     * Raw device observations. The OS version and screen facts (points and pixelRatio, the screen
     * scale; pxDensity keeps its older meaning) feed the model consistency checks;
     * left out when absent, so an older App's request serializes and hashes exactly as before.
     */
    public record Signals(Double memGB, Integer cores, String model, String brand, String gpu, Double pxDensity,
                          Double pingMs, Integer batteryLevel, Boolean charging, Boolean networkReachable,
                          String platform, String soc,
                          @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String osVersion,
                          @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Integer screenWidth,
                          @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Integer screenHeight,
                          @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Double pixelRatio,
                          @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) List<String> socIds) {
        public Signals(Double memGB, Integer cores, String model, String brand, String gpu, Double pxDensity,
                       Double pingMs, Integer batteryLevel, Boolean charging, Boolean networkReachable,
                       String platform, String soc, String osVersion, Integer screenWidth, Integer screenHeight, Double pixelRatio) {
            this(memGB, cores, model, brand, gpu, pxDensity, pingMs, batteryLevel, charging, networkReachable, platform, soc,
                    osVersion, screenWidth, screenHeight, pixelRatio, null);
        }
        public Signals(Double memGB, Integer cores, String model, String brand, String gpu, Double pxDensity,
                       Double pingMs, Integer batteryLevel, Boolean charging, Boolean networkReachable,
                       String platform, String soc) {
            this(memGB, cores, model, brand, gpu, pxDensity, pingMs, batteryLevel, charging, networkReachable, platform, soc,
                    null, null, null, null);
        }
        public Signals(Double memGB, Integer cores, String model, String brand, String gpu, Double pxDensity,
                       Double pingMs, Integer batteryLevel, Boolean charging, Boolean networkReachable) {
            this(memGB, cores, model, brand, gpu, pxDensity, pingMs, batteryLevel, charging, networkReachable, "", "");
        }
    }
}
