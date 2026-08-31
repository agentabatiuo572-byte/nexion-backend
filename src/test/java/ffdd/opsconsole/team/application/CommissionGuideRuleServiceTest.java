package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class CommissionGuideRuleServiceTest {

    @Test
    void projectsChangedCanonicalValuesWithoutSettlementDefaultsOrWrites() {
        PlatformConfigFacade config = mock(PlatformConfigFacade.class);
        LeadershipPoolConfigGuard leadership = mock(LeadershipPoolConfigGuard.class);
        Map<String, String> values = validValues();
        when(config.activeValue(anyString())).thenAnswer(call -> Optional.ofNullable(values.get(call.getArgument(0))));
        when(leadership.requireValid()).thenReturn(new LeadershipPoolConfigGuard.SettlementConfig(
                7L, new BigDecimal("0.07"), 5, new BigDecimal("12000"), "0 0 0 * * *", "ignored"));

        Map<String, Object> guide = new CommissionGuideRuleService(config, leadership)
                .guide(productionEnvironment());

        assertThat(guide).containsEntry("source", "server")
                .containsEntry("serverCanonical", true)
                .containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("runId", null)
                .containsEntry("coolingDays", 9);
        assertThat(group(guide, "network")).containsEntry("depthGateLayer", 6)
                .containsEntry("depthGateRank", 7)
                .containsEntry("exitCapRate", new BigDecimal("0.15"));
        assertThat(group(guide, "binary")).containsEntry("threshold", new BigDecimal("2500"))
                .containsEntry("matchRate", new BigDecimal("0.13"))
                .containsEntry("dailyCap", new BigDecimal("4200"))
                .containsEntry("settlePeriod", "weekly")
                .containsEntry("residualPolicy", "carryForward")
                .containsEntry("paused", false);
        assertThat(group(guide, "leadership")).containsEntry("rate", new BigDecimal("0.07"))
                .containsEntry("minRank", 5)
                .containsEntry("monthlyCap", new BigDecimal("12000"));
        assertThat(group(guide, "capabilities")).containsEntry("peer", false).containsEntry("genesis", false);
        verify(config, never()).activeValueForUpdate(anyString());
        verify(config, never()).upsertAdminValue(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void usesZeroOnlyWhereSettlementSemanticsAllowItAndNullsInvalidGroups() {
        PlatformConfigFacade config = mock(PlatformConfigFacade.class);
        LeadershipPoolConfigGuard leadership = mock(LeadershipPoolConfigGuard.class);
        Map<String, String> values = validValues();
        values.put("commission/cooling-days", "0");
        values.put("team.ui.F.unilevel.mergeExitMaxPct", "0%");
        values.put("team.ui.F.unilevel.depthGateRank", "V0");
        values.put("team.ui.F.binary.matchRate", "0");
        when(config.activeValue(anyString())).thenAnswer(call -> Optional.ofNullable(values.get(call.getArgument(0))));
        when(leadership.requireValid()).thenThrow(new LeadershipPoolConfigGuard.ConfigUnavailableException(
                "team.ui.F.pool.ratio", "MISSING", "absent"));

        Map<String, Object> guide = new CommissionGuideRuleService(config, leadership)
                .guide(productionEnvironment());

        assertThat(guide).containsEntry("coolingDays", 0).containsEntry("binary", null).containsEntry("leadership", null);
        assertThat(group(guide, "network")).containsEntry("depthGateRank", 0)
                .containsEntry("exitCapRate", BigDecimal.ZERO);
    }

    @Test
    void keepsTheNetworkObjectAndOnlyNullsMalformedIndividualFacts() {
        PlatformConfigFacade config = mock(PlatformConfigFacade.class);
        LeadershipPoolConfigGuard leadership = mock(LeadershipPoolConfigGuard.class);
        Map<String, String> values = validValues();
        values.put("team.ui.F.unilevel.depthGate", "L99");
        when(config.activeValue(anyString())).thenAnswer(call -> Optional.ofNullable(values.get(call.getArgument(0))));
        when(leadership.requireValid()).thenThrow(new LeadershipPoolConfigGuard.ConfigUnavailableException(
                "team.ui.F.pool.ratio", "MISSING", "absent"));

        Map<String, Object> guide = new CommissionGuideRuleService(config, leadership)
                .guide(productionEnvironment());

        assertThat(group(guide, "network")).containsEntry("depthGateLayer", null)
                .containsEntry("depthGateRank", 7)
                .containsEntry("exitCapRate", new BigDecimal("0.15"));
    }

    @Test
    void rejectsBinaryAmountsThatSettlementCannotRepresentAtSixDecimals() {
        PlatformConfigFacade config = mock(PlatformConfigFacade.class);
        LeadershipPoolConfigGuard leadership = mock(LeadershipPoolConfigGuard.class);
        Map<String, String> values = validValues();
        when(config.activeValue(anyString())).thenAnswer(call -> Optional.ofNullable(values.get(call.getArgument(0))));
        when(leadership.requireValid()).thenThrow(new LeadershipPoolConfigGuard.ConfigUnavailableException(
                "team.ui.F.pool.ratio", "MISSING", "absent"));
        var service = new CommissionGuideRuleService(config, leadership);
        values.put("team.ui.F.binary.threshold", "1000.0000001");
        assertThat(service.guide(productionEnvironment())).containsEntry("binary", null);
        values.put("team.ui.F.binary.threshold", "1000.0000000");
        values.put("growth.phase.month.3.binaryDailyCap", "4200.0000001");
        assertThat(service.guide(productionEnvironment())).containsEntry("binary", null);
        values.put("growth.phase.month.3.binaryDailyCap", "4200.0000000");
        assertThat(service.guide(productionEnvironment()).get("binary")).isNotNull();
    }

    @Test
    void reportsSandboxOnlyWithAValidRunIdAndRejectsInvalidProfile() {
        PlatformConfigFacade config = mock(PlatformConfigFacade.class);
        LeadershipPoolConfigGuard leadership = mock(LeadershipPoolConfigGuard.class);
        when(config.activeValue(anyString())).thenReturn(Optional.empty());
        when(leadership.requireValid()).thenThrow(new LeadershipPoolConfigGuard.ConfigUnavailableException(
                "team.ui.F.pool.ratio", "MISSING", "absent"));
        CommissionGuideRuleService service = new CommissionGuideRuleService(config, leadership);

        MockEnvironment sandbox = new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID", "guide-run-20260831");
        sandbox.setActiveProfiles("test");
        assertThat(service.guide(sandbox)).containsEntry("sourceEnvironment", "SANDBOX")
                .containsEntry("runId", "guide-run-20260831");

        MockEnvironment invalid = new MockEnvironment();
        invalid.setActiveProfiles("prod", "test");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.guide(invalid))
                .hasMessage("COMMISSION_GUIDE_CONFIG_PROFILE_INVALID");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> group(Map<String, Object> guide, String key) {
        return (Map<String, Object>) guide.get(key);
    }

    private MockEnvironment productionEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        return environment;
    }

    private Map<String, String> validValues() {
        Map<String, String> values = new HashMap<>();
        values.put("commission/cooling-days", "9");
        values.put("team.ui.F.unilevel.depthGate", "L6");
        values.put("team.ui.F.unilevel.depthGateRank", "V7");
        values.put("team.ui.F.unilevel.mergeExitMaxPct", "15%");
        values.put("team.ui.F.binary.threshold", "2500");
        values.put("team.ui.F.binary.matchRate", "13%");
        values.put("team.ui.F.binary.paused", "false");
        values.put("team.ui.F.binary.spillover", "on");
        values.put("team.ui.F.binary.settlePeriod", "每周");
        values.put("team.ui.F.binary.residualPolicy", "转结");
        values.put("team.ui.F.binary.gvResetCron", "0 0 0 1 * *");
        values.put("H1.rhythm.totalMonths", "12");
        values.put("H1.rhythm.currentMonth", "3");
        values.put("growth.phase.current", "P1");
        values.put("growth.phase.month.3.binaryDailyCap", "4200");
        return values;
    }
}
