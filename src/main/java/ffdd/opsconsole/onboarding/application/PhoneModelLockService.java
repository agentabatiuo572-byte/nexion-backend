package ffdd.opsconsole.onboarding.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.common.boundary.PhoneModelLockContract;
import ffdd.opsconsole.common.boundary.PhoneReplacementContract.Account;
import ffdd.opsconsole.onboarding.mapper.OnboardingCalibrationMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

/**
 * Support's view of each installation's locked model, and the release that lets a genuinely
 * replaced phone (for example a restored backup) lock its new model on the next calibration.
 */
@ApplicationService
@RequiredArgsConstructor
public class PhoneModelLockService implements PhoneModelLockContract {
    private final OnboardingCalibrationMapper mapper;
    private final PlatformConfigFacade config;
    private final AuditLogService audit;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public List<Map<String,Object>> locks(Account account) {
        PhoneCalibrationPolicy policy = policy();
        List<Map<String,Object>> out = new ArrayList<>();
        for (var row : mapper.modelLocks(account.userId(), account.sourceEnvironment(), account.runId())) out.add(view(row, policy));
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String,Object> release(Account account, String installationId, long actor, String reason, String key) {
        if (installationId == null || !installationId.matches("[A-Za-z0-9._:-]{1,128}")) throw new BizException(422, "ONBOARDING_DEVICE_INVALID");
        // The user row lock serializes this with the customer's own calibration.
        if (mapper.lockUserSandbox(account.userId()) == null) throw new BizException(404, "PHONE_INSTALLATION_NOT_FOUND");
        var row = find(account, installationId);
        if (row.lockModel() == null || row.lockModel().isBlank()) return view(row, policy());
        if (mapper.releaseModelLock(account.userId(), installationId, account.sourceEnvironment(), account.runId()) != 1) {
            throw new BizException(409, "PHONE_MODEL_LOCK_RELEASE_CONFLICT");
        }
        Map<String,Object> detail = new LinkedHashMap<>();
        detail.put("userId", account.userId());
        detail.put("installationId", installationId);
        detail.put("lockedModel", row.lockModel());
        detail.put("reportedModel", row.model());
        detail.put("pendingReason", row.pendingReason());
        detail.put("reason", reason);
        audit.recordRequired(AuditLogWriteRequest.builder().traceId(key).actorType("ADMIN").actorId(actor)
                .action("ADMIN.PHONE_MODEL_LOCK_RELEASED").resourceType("PHONE_INSTALLATION").resourceId(installationId)
                .result("SUCCEEDED").riskLevel("HIGH").detail(detail).build());
        return view(find(account, installationId), policy());
    }

    private OnboardingCalibrationMapper.ModelLockRow find(Account account, String installationId) {
        return mapper.modelLocks(account.userId(), account.sourceEnvironment(), account.runId()).stream()
                .filter(row -> installationId.equals(row.deviceId())).findFirst()
                .orElseThrow(() -> new BizException(404, "PHONE_INSTALLATION_NOT_FOUND"));
    }

    private PhoneCalibrationPolicy policy() {
        try {
            return new PhoneCalibrationConfigService(config, clock).activePolicy();
        } catch (BizException unavailable) {
            return null; // Support still sees the raw reports.
        }
    }

    private static Map<String,Object> view(OnboardingCalibrationMapper.ModelLockRow row, PhoneCalibrationPolicy policy) {
        boolean stored = row.lockModel() != null && !row.lockModel().isBlank();
        String lockPlatform = stored && row.lockPlatform() != null && !row.lockPlatform().isEmpty() ? row.lockPlatform() : row.platform();
        Map<String,Object> view = new LinkedHashMap<>();
        view.put("installationId", row.deviceId());
        view.put("platform", lockPlatform);
        view.put("lockedModel", stored ? name(policy, lockPlatform, row.lockModel()) : null);
        view.put("reportedModel", name(policy, row.platform(), row.model()));
        view.put("held", PhoneModelRisk.MODEL_CHANGED.equals(row.pendingReason()));
        view.put("releasable", stored);
        view.put("calibrationStatus", row.calibrationStatus());
        view.put("activationStatus", row.activationStatus());
        view.put("updatedAt", row.updatedAt());
        return view;
    }

    private static String name(PhoneCalibrationPolicy policy, String platform, String model) {
        if (model == null || model.isBlank()) return null;
        return policy == null ? model : policy.displayModel(platform, model);
    }
}
