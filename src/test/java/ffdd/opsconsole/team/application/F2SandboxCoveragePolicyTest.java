package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class F2SandboxCoveragePolicyTest {
    @Test
    void enablesOnlyForTheExplicitIsolatedProfileAndMatchingRun() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("nexion.acceptance.f2.coverage-override-enabled", "true")
                .withProperty("nexion.acceptance.f2.coverage-override-run-id", "TEAM-RUN-20260816")
                .withProperty("NEXION_ACCEPTANCE_RUN_ID", "TEAM-RUN-20260816");
        environment.setActiveProfiles("dev");

        assertThat(F2SandboxCoveragePolicy.isOverrideActive(environment)).isTrue();
    }

    @Test
    void rejectsProductionAndMismatchedRuns() {
        MockEnvironment production = new MockEnvironment()
                .withProperty("nexion.acceptance.f2.coverage-override-enabled", "true")
                .withProperty("nexion.acceptance.f2.coverage-override-run-id", "TEAM-RUN-20260816")
                .withProperty("NEXION_ACCEPTANCE_RUN_ID", "TEAM-RUN-20260816");
        production.setActiveProfiles("prod");
        assertThat(F2SandboxCoveragePolicy.isOverrideActive(production)).isFalse();

        MockEnvironment mismatched = new MockEnvironment()
                .withProperty("nexion.acceptance.f2.coverage-override-enabled", "true")
                .withProperty("nexion.acceptance.f2.coverage-override-run-id", "TEAM-RUN-20260816")
                .withProperty("NEXION_ACCEPTANCE_RUN_ID", "TEAM-RUN-20260817");
        mismatched.setActiveProfiles("dev");
        assertThat(F2SandboxCoveragePolicy.isOverrideActive(mismatched)).isFalse();
    }
}
