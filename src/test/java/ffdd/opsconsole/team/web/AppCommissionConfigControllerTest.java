package ffdd.opsconsole.team.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.team.application.OpsTeamService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AppCommissionConfigControllerTest {
    @Test
    void projectsTheCanonicalF2RatesAndProductionProvenance() {
        var service = mock(OpsTeamService.class);
        when(service.rates()).thenReturn(ApiResult.ok(Map.of(
                "unilevelRates", List.of(Map.of("level", "L1", "usdtPct", 10, "nexReward", 50)),
                "configValues", Map.of(
                        "F.cooldown", "21",
                        "F.promo.weekMultiplier", "1.25",
                        "F.unilevel.L1.paused", "true"))));

        var environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        var result = new AppCommissionConfigController(service, environment).rates();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("serverCanonical", true)
                .containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("runId", null)
                .containsEntry("coolingDays", "21")
                .containsEntry("promoMultiplier", "1.25")
                .containsEntry("unilevelPaused", Map.of(
                        "L1", true, "L2", false, "L3", false, "L4", false,
                        "L5", false, "L6", false, "L7", false));
        assertThat(result.getData().get("unilevel")).isEqualTo(List.of(Map.of("level", "L1", "usdtPct", 10, "nexReward", 50)));
    }

    @Test
    void developmentUsesProductionAuthorityWhileIsolatedTestRequiresSandboxRunId() {
        var service = mock(OpsTeamService.class);
        when(service.rates()).thenReturn(ApiResult.ok(Map.of("unilevelRates", List.of(), "configValues", Map.of())));
        var development = new MockEnvironment();
        development.setActiveProfiles("dev");

        var developmentResult = new AppCommissionConfigController(service, development).rates();
        assertThat(developmentResult.getData()).containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("runId", null);

        var isolated = new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID", "commission-run-20260817");
        isolated.setActiveProfiles("test");
        var isolatedResult = new AppCommissionConfigController(service, isolated).rates();
        assertThat(isolatedResult.getData()).containsEntry("sourceEnvironment", "SANDBOX")
                .containsEntry("runId", "commission-run-20260817");

        var missing = new MockEnvironment();
        missing.setActiveProfiles("test");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AppCommissionConfigController(service, missing).rates())
                .hasMessage("COMMISSION_CONFIG_SANDBOX_RUN_ID_REQUIRED");
    }
}
