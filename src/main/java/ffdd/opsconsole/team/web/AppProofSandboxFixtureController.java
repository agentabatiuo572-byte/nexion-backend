package ffdd.opsconsole.team.web;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.team.application.AppProofSandboxFixtureService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Automated-test fixture surface. It is never registered by a deployable runtime profile. */
@RestController
@Profile("test")
@RequestMapping("/api/app")
@RequiredArgsConstructor
public class AppProofSandboxFixtureController {
    private final AppProofSandboxFixtureService sandboxFixtures;
    private final Environment environment;

    @PostMapping("/proof/sandbox-fixture")
    public ApiResult<Map<String, Object>> put(Authentication authentication,
                                              @RequestBody SandboxFixtureRequest request) {
        Long userId = authenticatedUserId(authentication);
        String currentRun = environment.getProperty("NEXION_ACCEPTANCE_RUN_ID");
        if (userId == null || request == null || currentRun == null || !currentRun.equals(request.runId())) {
            return ApiResult.fail(403, "PROOF_SANDBOX_FIXTURE_SCOPE_INVALID");
        }
        sandboxFixtures.put(currentRun, userId, new AppProofSandboxFixtureService.Fixture(
                request.earningsTotalUsdt(), request.currentStreak(), request.longestStreak(),
                request.lastCheckInDate(), request.higherCount(), request.populationCount()));
        return ApiResult.ok(Map.of("source", "mock", "sourceEnvironment", "SANDBOX", "runId", currentRun));
    }

    private static Long authenticatedUserId(Authentication authentication) {
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

    public record SandboxFixtureRequest(String runId, BigDecimal earningsTotalUsdt,
                                        Integer currentStreak, Integer longestStreak,
                                        LocalDate lastCheckInDate, Long higherCount,
                                        Long populationCount) { }
}
