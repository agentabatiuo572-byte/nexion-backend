package ffdd.opsconsole.market.web;

import ffdd.opsconsole.market.application.AppGenesisSandboxFixtureService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Automated-test fixture surface. It is never registered by a deployable runtime profile. */
@RestController
@Profile("test")
@RequiredArgsConstructor
public class AppGenesisSandboxFixtureController {
    private final AppGenesisSandboxFixtureService sandboxFixtures;

    @PostMapping("/api/genesis/sandbox-fixture")
    public ApiResult<Map<String, Object>> replace(Authentication authentication,
                                                  @RequestBody SandboxFixtureRequest request) {
        Long userId = userId(authentication);
        if (userId == null) return ApiResult.fail(403, "USER_SUBJECT_REQUIRED");
        String runId = request == null ? null : request.runId();
        sandboxFixtures.replace(runId, userId, request == null ? null : request.holders());
        return ApiResult.ok(Map.of("serverCanonical", true, "source", "mock",
                "sourceEnvironment", "SANDBOX", "runId", runId, "fixture", "GENESIS_HOLDER"));
    }

    @DeleteMapping("/api/genesis/sandbox-fixture")
    public ApiResult<Map<String, Object>> clear(Authentication authentication, @RequestParam String runId) {
        Long userId = userId(authentication);
        if (userId == null) return ApiResult.fail(403, "USER_SUBJECT_REQUIRED");
        sandboxFixtures.clear(runId, userId);
        return ApiResult.ok(Map.of("serverCanonical", true, "source", "mock",
                "sourceEnvironment", "SANDBOX", "runId", runId, "cleared", true));
    }

    private static Long userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal() == null
                || !(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(String.valueOf(details.get("subjectType")))) return null;
        try {
            long value = Long.parseLong(String.valueOf(authentication.getPrincipal()));
            return value > 0 ? value : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    public record SandboxFixtureRequest(
            String runId, List<AppGenesisSandboxFixtureService.HolderSpec> holders) { }
}
