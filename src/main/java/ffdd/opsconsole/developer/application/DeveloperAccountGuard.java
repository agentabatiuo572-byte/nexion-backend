package ffdd.opsconsole.developer.application;

import ffdd.opsconsole.developer.mapper.AppDeveloperAccessMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.Arrays;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Shared account and approval boundary for all developer resources. */
@Component
@RequiredArgsConstructor
public class DeveloperAccountGuard {
    private final AppDeveloperAccessMapper access;
    private final Environment environment;

    public Scope requireApproved(Long userId, boolean lock) {
        Scope scope = scope(userId, lock);
        if (access.approved(userId, scope.sourceEnvironment(), scope.runId()) <= 0) {
            throw new BizException(403, "DEVELOPER_ACCESS_APPROVAL_REQUIRED");
        }
        return scope;
    }

    public Scope scope(Long userId, boolean lock) {
        if (userId == null || userId <= 0) throw new BizException(403, "USER_AUTH_REQUIRED");
        Integer sandbox = lock ? access.lockUserSandbox(userId) : access.userSandbox(userId);
        if (sandbox == null) throw new BizException(403, "USER_AUTH_REQUIRED");
        Set<String> profiles = Arrays.stream(environment.getActiveProfiles())
                .map(String::trim).map(String::toLowerCase).filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.toSet());
        boolean isolated = profiles.size() == 1 && Set.of("dev", "test").contains(profiles.iterator().next());
        boolean production = profiles.isEmpty() || (profiles.size() == 1 && Set.of("prod").contains(profiles.iterator().next()));
        if (!isolated && !production) throw new BizException(503, "DEVELOPER_ACCESS_PROFILE_INVALID");
        if (isolated && sandbox != 1) throw new BizException(403, "DEVELOPER_ACCESS_SANDBOX_USER_REQUIRED");
        if (production && sandbox != 0) throw new BizException(403, "DEVELOPER_ACCESS_PRODUCTION_USER_REQUIRED");
        if (!isolated) return new Scope("PRODUCTION", "");
        String runId = environment.getProperty("NEXION_ACCEPTANCE_RUN_ID");
        if (runId == null || !runId.trim().matches("[A-Za-z0-9][A-Za-z0-9._-]{2,63}")) {
            throw new BizException(503, "DEVELOPER_ACCESS_SANDBOX_RUN_ID_REQUIRED");
        }
        return new Scope("SANDBOX", runId.trim());
    }

    public record Scope(String sourceEnvironment, String runId) { }
}
