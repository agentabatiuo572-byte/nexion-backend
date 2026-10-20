package ffdd.opsconsole.onboarding.application;

import ffdd.opsconsole.onboarding.mapper.PhoneInstallationMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/** Business installation identity and current execution ownership; no hardware authenticity claim. */
@Service
@RequiredArgsConstructor
public class PhoneInstallationService {
    private final PhoneInstallationMapper mapper;
    private final ffdd.opsconsole.shared.security.CurrentAppSessionService currentSessions;

    public void requireSystemExecution(long userId, long deviceId) {
        lockUser(userId);
        var binding = mapper.deviceBinding(userId, deviceId);
        if (binding == null) throw new BizException(404, "TASK_ASSIGNMENT_DEVICE_NOT_FOUND");
        if ("PHONE".equalsIgnoreCase(binding.deviceType()) || "MOBILE".equalsIgnoreCase(binding.deviceType())) {
            currentSessions.requireExecutionOwner(userId, binding.installationId());
            requireExecution(userId, binding.installationId());
        }
    }

    public void requireCurrent(Authentication auth, String installationId, boolean executionRequired) {
        require(auth, installationId);
        currentSessions.requireCurrent(auth, installationId, executionRequired);
    }

    public void requireDefer(Authentication auth, String installationId, boolean initialOnly) {
        require(auth, installationId);
        var session = currentSessions.requireSession(auth);
        if ("H5".equals(session.getSessionGroup())) {
            if (!initialOnly) throw new BizException(403, "PHONE_INITIAL_DEFER_ONLY");
        } else currentSessions.requireCurrent(auth, installationId, false);
    }

    public void require(Authentication auth, String installationId) {
        userId(auth);
        validateInstallationId(installationId);
    }

    public void requireForDevice(Authentication auth, Long deviceId, String installationId) {
        long userId = userId(auth);
        if (deviceId == null || deviceId <= 0) throw new BizException(422, "TASK_ASSIGNMENT_DEVICE_REQUIRED");
        lockUser(userId);
        requireDeviceBinding(auth, userId, deviceId, installationId);
    }

    private void requireDeviceBinding(Authentication auth, long userId, long deviceId, String installationId) {
        var binding = mapper.deviceBinding(userId, deviceId);
        if (binding == null) throw new BizException(404, "TASK_ASSIGNMENT_DEVICE_NOT_FOUND");
        if ("PHONE".equalsIgnoreCase(binding.deviceType()) || "MOBILE".equalsIgnoreCase(binding.deviceType())) {
            currentSessions.requireCurrent(auth, installationId, true);
            validateInstallationId(installationId);
            if (!installationId.equals(binding.installationId())) {
                throw new BizException(409, "TASK_ASSIGNMENT_PHONE_BINDING_INVALID");
            }
            requireExecution(userId, installationId);
        }
    }

    public void requireRuntime(Authentication auth, String installationId) {
        long userId = userId(auth);
        validateInstallationId(installationId);
        lockUser(userId);
        currentSessions.requireCurrent(auth, installationId, true);
        requireExecution(userId, installationId);
    }

    public void requireForTask(Authentication auth, String taskNo, String installationId) {
        long userId = userId(auth);
        lockUser(userId);
        Long deviceId = mapper.taskDevice(userId, taskNo);
        if (deviceId == null) throw new BizException(404, "TASK_ASSIGNMENT_NOT_FOUND");
        requireDeviceBinding(auth, userId, deviceId, installationId);
    }

    private void requireExecution(long userId, String installationId) {
        if (installationId == null || !installationId.equals(mapper.executionInstallation(userId))) {
            throw new BizException(409, "TASK_ASSIGNMENT_PHONE_BINDING_INVALID");
        }
    }

    private void lockUser(long userId) {
        if (mapper.lockUser(userId) == null) throw new BizException(403, "USER_AUTH_REQUIRED");
    }

    private static long userId(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || !(auth.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(details.get("subjectType"))) throw new BizException(403, "USER_AUTH_REQUIRED");
        try {
            long id = Long.parseLong(String.valueOf(auth.getPrincipal()));
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException invalid) { throw new BizException(403, "USER_AUTH_REQUIRED"); }
    }

    private static void validateInstallationId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new BizException(422, "ONBOARDING_DEVICE_INVALID");
        }
    }
}
