package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.team.mapper.AppAmbassadorPolicyMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

class AppAmbassadorPolicyServiceTest {
    private final AppAmbassadorPolicyMapper mapper = mock(AppAmbassadorPolicyMapper.class);
    private final Environment environment = mock(Environment.class);
    private final AppAmbassadorPolicyService service = new AppAmbassadorPolicyService(mapper, environment);

    @Test
    void returnsServerPolicyWithProductionProvenanceAndDefaultBudget() {
        when(environment.getActiveProfiles()).thenReturn(new String[0]);
        when(mapper.user(9L)).thenReturn(new AppAmbassadorPolicyMapper.UserScope(0, "V5"));
        when(mapper.policy()).thenReturn(new AppAmbassadorPolicyMapper.PolicyRow("ambassador-v1", 3L,
                new BigDecimal("3000.000000"), "[{\"id\":\"venue\",\"title\":\"Event venue\",\"range\":\"$1,000 — $10,000\",\"rule\":\"Host an event\",\"minBudgetUsdt\":1000,\"maxBudgetUsdt\":10000},{\"id\":\"kol\",\"title\":\"KOL\",\"range\":\"$500 — $5,000\",\"rule\":\"Creator\",\"minBudgetUsdt\":500,\"maxBudgetUsdt\":5000},{\"id\":\"print\",\"title\":\"Print\",\"range\":\"$1,000 — $8,000\",\"rule\":\"Visibility\",\"minBudgetUsdt\":1000,\"maxBudgetUsdt\":8000},{\"id\":\"dev\",\"title\":\"Developer\",\"range\":\"$300 — $3,000\",\"rule\":\"Workshop\",\"minBudgetUsdt\":300,\"maxBudgetUsdt\":3000}]"));

        var result = service.policy(9L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("source", "server")
                .containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("runId", "")
                .containsEntry("defaultBudgetUsdt", new BigDecimal("3000.000000"));
        assertThat(result.getData().get("buckets")).isInstanceOf(java.util.List.class);
    }

    @Test
    void rejectsMissingPolicyInsteadOfInventingClientDefaults() {
        when(environment.getActiveProfiles()).thenReturn(new String[0]);
        when(mapper.user(9L)).thenReturn(new AppAmbassadorPolicyMapper.UserScope(0, "V5"));
        when(mapper.policy()).thenReturn(null);

        var result = service.policy(9L);

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("AMBASSADOR_POLICY_UNAVAILABLE");
    }

    @Test
    void emitsSandboxProvenanceFromTheConfiguredAcceptanceRun() {
        when(environment.getActiveProfiles()).thenReturn(new String[] { "test" });
        when(environment.getProperty("NEXION_ACCEPTANCE_RUN_ID", "")).thenReturn("sandbox-run-20260816");
        when(mapper.user(9L)).thenReturn(new AppAmbassadorPolicyMapper.UserScope(1, "V5"));
        when(mapper.policy()).thenReturn(new AppAmbassadorPolicyMapper.PolicyRow("ambassador-v1", 3L,
                new BigDecimal("3000.000000"), "[{\"id\":\"venue\",\"title\":\"Event venue\",\"range\":\"$1,000 — $10,000\",\"rule\":\"Host\",\"minBudgetUsdt\":1000,\"maxBudgetUsdt\":10000},{\"id\":\"kol\",\"title\":\"KOL\",\"range\":\"$500 — $5,000\",\"rule\":\"Creator\",\"minBudgetUsdt\":500,\"maxBudgetUsdt\":5000},{\"id\":\"print\",\"title\":\"Print\",\"range\":\"$1,000 — $8,000\",\"rule\":\"Visibility\",\"minBudgetUsdt\":1000,\"maxBudgetUsdt\":8000},{\"id\":\"dev\",\"title\":\"Developer\",\"range\":\"$300 — $3,000\",\"rule\":\"Workshop\",\"minBudgetUsdt\":300,\"maxBudgetUsdt\":3000}]"));

        var result = service.policy(9L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("sourceEnvironment", "SANDBOX")
                .containsEntry("runId", "sandbox-run-20260816");
    }

    @Test
    void developmentAllowsAnyActiveDevelopmentAccountAndProductionProvenance() {
        when(environment.getActiveProfiles()).thenReturn(new String[] { "dev" });
        when(mapper.user(9L)).thenReturn(new AppAmbassadorPolicyMapper.UserScope(1, "V5"));
        when(mapper.policy()).thenReturn(new AppAmbassadorPolicyMapper.PolicyRow("ambassador-v1", 3L,
                new BigDecimal("3000.000000"), "[{\"id\":\"venue\",\"title\":\"Event venue\",\"range\":\"$1,000 — $10,000\",\"rule\":\"Host\",\"minBudgetUsdt\":1000,\"maxBudgetUsdt\":10000},{\"id\":\"kol\",\"title\":\"KOL\",\"range\":\"$500 — $5,000\",\"rule\":\"Creator\",\"minBudgetUsdt\":500,\"maxBudgetUsdt\":5000},{\"id\":\"print\",\"title\":\"Print\",\"range\":\"$1,000 — $8,000\",\"rule\":\"Visibility\",\"minBudgetUsdt\":1000,\"maxBudgetUsdt\":8000},{\"id\":\"dev\",\"title\":\"Developer\",\"range\":\"$300 — $3,000\",\"rule\":\"Workshop\",\"minBudgetUsdt\":300,\"maxBudgetUsdt\":3000}]"));

        var result = service.policy(9L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("sourceEnvironment", "PRODUCTION").containsEntry("runId", "");
    }
}
