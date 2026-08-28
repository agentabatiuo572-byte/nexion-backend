package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.mapper.SupportAcceptanceSandboxMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Keeps canonical support facts on the account class selected by the Java profile. */
@Component
@RequiredArgsConstructor
public class ProductionSupportPathGuard {
    private final Environment environment;
    private final SupportAcceptanceSandboxMapper mapper;

    public void requireAllowed(Long userId) {
        RuntimeProfile profile = runtimeProfile();
        if (profile == RuntimeProfile.TEST || profile == RuntimeProfile.INVALID) {
            throw new BizException(409, "SUPPORT_PRODUCTION_PATH_FORBIDDEN");
        }
        if (userId == null) return;
        Integer sandbox = mapper.sandboxUser(userId);
        boolean allowed = profile == RuntimeProfile.DEVELOPMENT
                ? Integer.valueOf(1).equals(sandbox)
                : Integer.valueOf(0).equals(sandbox);
        if (!allowed) {
            throw new BizException(409, "SUPPORT_PRODUCTION_PATH_FORBIDDEN");
        }
    }

    /** Ops writes target the same canonical tables in development and production. */
    public void requireOpsWriteAllowed() {
        RuntimeProfile profile = runtimeProfile();
        if (profile == RuntimeProfile.TEST || profile == RuntimeProfile.INVALID) {
            throw new BizException(409, "SUPPORT_PRODUCTION_PATH_FORBIDDEN");
        }
    }

    private RuntimeProfile runtimeProfile() {
        Set<String> profiles = Arrays.stream(environment.getActiveProfiles())
                .map(value -> value == null ? "" : value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .collect(Collectors.toSet());
        if (profiles.isEmpty()) return RuntimeProfile.PRODUCTION;
        if (profiles.size() != 1) return RuntimeProfile.INVALID;
        return switch (profiles.iterator().next()) {
            case "dev" -> RuntimeProfile.DEVELOPMENT;
            case "test" -> RuntimeProfile.TEST;
            case "prod" -> RuntimeProfile.PRODUCTION;
            default -> RuntimeProfile.INVALID;
        };
    }

    /** Background jobs may touch canonical support facts in development or production. */
    public boolean productionSupportAutomationAllowed() {
        RuntimeProfile profile = runtimeProfile();
        return profile == RuntimeProfile.DEVELOPMENT || profile == RuntimeProfile.PRODUCTION;
    }

    private enum RuntimeProfile { DEVELOPMENT, TEST, PRODUCTION, INVALID }
}
