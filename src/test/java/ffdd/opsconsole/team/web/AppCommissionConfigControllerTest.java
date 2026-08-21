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
                "configValues", Map.of("F.cooldown", "21", "F.promo.weekMultiplier", "1.25"))));

        var result = new AppCommissionConfigController(service, new MockEnvironment()).rates();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("serverCanonical", true)
                .containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("runId", null)
                .containsEntry("coolingDays", "21")
                .containsEntry("promoMultiplier", "1.25");
        assertThat(result.getData().get("unilevel")).isEqualTo(List.of(Map.of("level", "L1", "usdtPct", 10, "nexReward", 50)));
    }

    @Test
    void sandboxCarriesTheRequiredRunIdAndRejectsMissingRunId() {
        var service = mock(OpsTeamService.class);
        when(service.rates()).thenReturn(ApiResult.ok(Map.of("unilevelRates", List.of(), "configValues", Map.of())));
        var environment = new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID", "commission-run-20260817");
        environment.setActiveProfiles("dev");

        var result = new AppCommissionConfigController(service, environment).rates();
        assertThat(result.getData()).containsEntry("sourceEnvironment", "SANDBOX")
                .containsEntry("runId", "commission-run-20260817");

        var missing = new MockEnvironment();
        missing.setActiveProfiles("dev");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AppCommissionConfigController(service, missing).rates())
                .hasMessage("COMMISSION_CONFIG_SANDBOX_RUN_ID_REQUIRED");
    }
}
