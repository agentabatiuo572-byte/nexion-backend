package ffdd.opsconsole.onboarding.application;

import ffdd.opsconsole.common.boundary.PhoneReplacementContract.*;
import ffdd.opsconsole.common.boundary.PhoneReplacementSupportContract;
import ffdd.opsconsole.growth.application.WheelSandboxProfile;
import ffdd.opsconsole.onboarding.mapper.OnboardingCalibrationMapper;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.CurrentAppSessionService;
import ffdd.opsconsole.shared.security.UserAuthEnvironment;
import ffdd.opsconsole.shared.security.infrastructure.UserSessionEntity;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** HTTP application boundary: target identity always comes from the persisted current session. */
@Service
@RequiredArgsConstructor
public class AppPhoneReplacementService {
    private final PhoneReplacementService replacements;
    private final CurrentAppSessionService sessions;
    private final OnboardingCalibrationMapper calibration;
    private final WheelSandboxProfile runtime;
    private final Environment environment;
    private final PhoneReplacementSupportContract support;
    private final OnboardingCalibrationService onboarding;

    public record RequestInput(Long expectedBindingVersion, String reason) { }
    public record SupportInput(String supportRef) { }
    private record Context(Account account, UserSessionEntity session) { }

    @Transactional
    public Eligibility eligibility(Authentication authentication) {
        replacements.lockPolicy();
        Context context = context(authentication, false);
        var session = context.session();
        var account = context.account();
        var binding = calibration.phoneBinding(account.userId(), account.sourceEnvironment(), account.runId());
        var blocks = new java.util.ArrayList<String>();
        if (binding != null && calibration.phoneReplacementHardPause(account.userId(), binding.userDeviceId()) != null)
            blocks.add("PHONE_REPLACEMENT_RISK_PAUSED");
        String hardwareKey = null;
        if ("APP".equals(session.getSessionGroup())) {
            blocks.addAll(onboarding.activationBlockReasons(account.userId(), session.getInstallationId()));
            var calibrated = "SANDBOX".equals(account.sourceEnvironment())
                    ? calibration.findScoped(account.userId(), session.getInstallationId(), account.sourceEnvironment(), account.runId())
                    : calibration.find(account.userId(), session.getInstallationId());
            if (calibrated != null) hardwareKey = onboarding.hardwareKey(calibrated);
        }
        return replacements.eligibility(context.account(), session.getInstallationId(), hardwareKey,
                "APP".equals(session.getSessionGroup()) && Boolean.TRUE.equals(session.getExecutionSupported()), blocks);
    }

    @Transactional
    public CurrentRequest currentRequest(Authentication authentication) {
        Context context = context(authentication, false);
        return replacements.currentRequest(context.account(), "APP".equals(context.session().getSessionGroup())
                ? context.session().getInstallationId() : null);
    }

    @Transactional
    public Receipt operation(Authentication authentication, String operationId) {
        Context context = context(authentication, false);
        return replacements.userOperation(context.account(), operationId);
    }

    @Transactional
    public Receipt operationByKey(Authentication authentication, String commandType, String key) {
        Context context = context(authentication, false);
        return replacements.operationByKey(context.account(), new Actor("USER", context.account().userId()), commandType, key);
    }

    @Transactional(rollbackFor = Exception.class)
    public Receipt createRequest(Authentication authentication, RequestInput input, String key) {
        if (input == null || input.expectedBindingVersion() == null || input.reason() == null)
            throw new BizException(422, "PHONE_REPLACEMENT_REQUEST_INVALID");
        var policy = replacements.lockPolicy();
        Context context = context(authentication, true);
        var locked = replacements.lockAccount(policy, context.account(), null);
        var command = replacements.openOperation(locked, new Actor("USER", context.account().userId()),
                "PHONE_REPLACEMENT_REQUEST", key,
                Map.of("input", input, "installationId", context.session().getInstallationId()));
        return replacements.createRequest(locked, context.session().getInstallationId(), input.expectedBindingVersion(), input.reason(), command);
    }

    @Transactional(rollbackFor = Exception.class)
    public Receipt linkSupport(Authentication authentication, String requestId, SupportInput input, String key) {
        if (input == null || input.supportRef() == null) throw new BizException(422, "SUPPORT_REFERENCE_INVALID");
        var policy = replacements.lockPolicy();
        Context context = context(authentication, true);
        var locked = replacements.lockAccount(policy, context.account(), null);
        var command = replacements.openOperation(locked, new Actor("USER", context.account().userId()),
                "PHONE_REPLACEMENT_SUPPORT_LINK", key, Map.of("requestId", requestId, "supportRef", input.supportRef()));
        // Permissions and current ownership are checked again even when recovering an old command.
        support.requireConversationOwner(context.account().userId(), input.supportRef());
        return replacements.linkSupport(locked, requestId, input.supportRef(), context.account().userId(), command);
    }

    private Context context(Authentication authentication, boolean appRequired) {
        var session = sessions.requireSession(authentication);
        long userId = session.getUserId();
        if (appRequired) sessions.requireCurrent(authentication, session.getInstallationId(), false);
        var authEnvironment = UserAuthEnvironment.resolve(environment)
                .orElseThrow(() -> new BizException(503, "ONBOARDING_RUNTIME_PROFILE_UNSUPPORTED"));
        if (!authEnvironment.acceptsSandbox(calibration.userSandbox(userId)))
            throw new BizException(403, "ONBOARDING_USER_ENVIRONMENT_MISMATCH");
        runtime.requireKnownRuntime();
        var account = runtime.mode() == WheelSandboxProfile.Mode.SANDBOX
                ? new Account(userId, "SANDBOX", runtime.requireRunId()) : Account.production(userId);
        return new Context(account, session);
    }
}
