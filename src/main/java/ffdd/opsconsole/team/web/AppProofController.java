package ffdd.opsconsole.team.web;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.team.application.AppProofService;
import ffdd.opsconsole.team.application.AppProofSandboxFixtureService;
import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/app")
@RequiredArgsConstructor
public class AppProofController {
    private final AppProofService service;
    private final AppProofSandboxFixtureService sandboxFixtures;
    private final Environment environment;

    @GetMapping("/proof")
    public ApiResult<Map<String, Object>> proof(Authentication authentication) {
        Long userId = authenticatedUserId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_AUTH_REQUIRED") : service.snapshot(userId);
    }

    /** Local acceptance-only fixture interface; production profiles reject it. */
    @PostMapping("/proof/sandbox-fixture")
    public ApiResult<Map<String, Object>> sandboxFixture(Authentication authentication,
                                                         @RequestBody SandboxFixtureRequest request) {
        if (!isSandboxProfile()) return ApiResult.fail(404, "PROOF_SANDBOX_FIXTURE_NOT_FOUND");
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

    private boolean isSandboxProfile() {
        return FundsSandboxProfileGuard.isStrictIsolatedProfile(environment.getActiveProfiles());
    }

    public record SandboxFixtureRequest(String runId, java.math.BigDecimal earningsTotalUsdt,
                                        Integer currentStreak, Integer longestStreak,
                                        java.time.LocalDate lastCheckInDate, Long higherCount,
                                        Long populationCount) { }

    private Long authenticatedUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal() == null
                || !(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(String.valueOf(details.get("subjectType")))) return null;
        try { long value = Long.parseLong(String.valueOf(authentication.getPrincipal())); return value > 0 ? value : null; }
        catch (NumberFormatException exception) { return null; }
    }
}
