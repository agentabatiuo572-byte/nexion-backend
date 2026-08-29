package ffdd.opsconsole.team.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.team.domain.TeamCommissionRepository;
import ffdd.opsconsole.team.domain.VRankEvaluationSnapshot;
import ffdd.opsconsole.team.domain.VRankPerformanceRepository;
import ffdd.opsconsole.team.mapper.AppTeamInsightsMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.Authentication;

class AppVRankControllerTest {
    @Test
    void ladderCarriesTheSameProductionProvenanceContract() {
        var commission = mock(TeamCommissionRepository.class);
        when(commission.unilevelRates()).thenReturn(List.of());
        when(commission.vRankRows()).thenReturn(List.of());
        var result = new AppVRankController(commission, mock(VRankPerformanceRepository.class),
                mock(AppTeamInsightsMapper.class), new MockEnvironment()).ranks();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("serverCanonical", true)
                .containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("runId", "");
    }

    @Test
    void rankLadderExposesOnlyEngineBackedPolicySnapshot() {
        var commission = mock(TeamCommissionRepository.class);
        when(commission.vRankRows()).thenReturn(List.of(Map.of(
                "v", "V0", "label", "Cadet", "visible", 1,
                "unilevelDepth", "L1", "peerBonusRate", BigDecimal.ZERO, "votes", 0)));
        when(commission.unilevelRates()).thenReturn(List.of(Map.of("level", "L1", "usdtPct", BigDecimal.TEN)));
        when(commission.selectVRankRewardRulesByRank("V0")).thenReturn(List.of());

        var result = new AppVRankController(commission, mock(VRankPerformanceRepository.class),
                mock(AppTeamInsightsMapper.class), new MockEnvironment()).ranks();

        assertThat(result.getData()).containsKey("policySnapshot");
        @SuppressWarnings("unchecked")
        Map<String, Object> policy = (Map<String, Object>) result.getData().get("policySnapshot");
        assertThat(policy)
                .containsEntry("source", "VRankPromotionEngine")
                .containsEntry("promotionMode", "STEPWISE")
                .containsEntry("conditionSemantics", "POSITIVE_FIELDS_ONLY");
    }

    @Test
    void productionRequiresProductionUserAndReturnsCanonicalProvenance() {
        var commission = mock(TeamCommissionRepository.class);
        var performance = mock(VRankPerformanceRepository.class);
        var users = mock(AppTeamInsightsMapper.class);
        when(users.userScope(7L)).thenReturn(new AppTeamInsightsMapper.UserScope(0, "V2"));
        when(commission.currentMemberVRank(7L)).thenReturn("V2");
        when(performance.computeSnapshot(7L)).thenReturn(new VRankEvaluationSnapshot(
                new BigDecimal("1198"), new BigDecimal("5240"), 5, Map.of(1, 3)));

        var result = new AppVRankController(commission, performance, users, new MockEnvironment())
                .current(userAuthentication(7L));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("serverCanonical", true)
                .containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("runId", "");
        verify(users).userScope(7L);
        verify(performance).computeSnapshot(7L);
        verify(commission).currentMemberVRank(7L);
    }

    @Test
    void developmentAllowsAnyActiveDevelopmentAccountAndCanonicalRankFacts() {
        var commission = mock(TeamCommissionRepository.class);
        var performance = mock(VRankPerformanceRepository.class);
        var users = mock(AppTeamInsightsMapper.class);
        when(users.userScope(7L)).thenReturn(new AppTeamInsightsMapper.UserScope(0, "V2"));
        when(commission.currentMemberVRank(7L)).thenReturn("V2");
        when(performance.computeSnapshot(7L)).thenReturn(new VRankEvaluationSnapshot(
                new BigDecimal("1198"), new BigDecimal("5240"), 5, Map.of(1, 3)));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");

        var result = new AppVRankController(commission, performance, users, environment)
                .current(userAuthentication(7L));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("sourceEnvironment", "PRODUCTION").containsEntry("runId", "");
        verify(performance).computeSnapshot(7L);
        verify(commission).currentMemberVRank(7L);
    }

    @Test
    void sandboxCurrentRankIsServerGeneratedAndStableByRunAndAccount() {
        var commission = mock(TeamCommissionRepository.class);
        var performance = mock(VRankPerformanceRepository.class);
        var users = mock(AppTeamInsightsMapper.class);
        when(users.userScope(7L)).thenReturn(new AppTeamInsightsMapper.UserScope(1, "V2"));
        var environment = new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID", "sandbox-run-20260816");
        environment.setActiveProfiles("test");
        var controller = new AppVRankController(commission, performance, users, environment);

        var first = controller.current(userAuthentication(7L));
        var repeat = controller.current(userAuthentication(7L));
        when(users.userScope(8L)).thenReturn(new AppTeamInsightsMapper.UserScope(1, "V2"));
        var otherAccount = controller.current(userAuthentication(8L));
        assertThat(first.getCode()).isZero();
        assertThat(first.getData()).isEqualTo(repeat.getData());
        assertThat(first.getData()).isNotEqualTo(otherAccount.getData());
        assertThat(first.getData()).containsEntry("sourceEnvironment", "SANDBOX")
                .containsEntry("runId", "sandbox-run-20260816");
        verify(users, times(2)).userScope(7L);
        verify(users).userScope(8L);
        verifyNoInteractions(commission, performance);
    }

    @Test
    void sandboxRejectsProductionUserBeforeReadingProductionFacts() {
        var commission = mock(TeamCommissionRepository.class);
        var performance = mock(VRankPerformanceRepository.class);
        var users = mock(AppTeamInsightsMapper.class);
        when(users.userScope(7L)).thenReturn(new AppTeamInsightsMapper.UserScope(0, "V2"));
        var environment = new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID", "sandbox-run-20260816");
        environment.setActiveProfiles("test");

        assertThatThrownBy(() -> new AppVRankController(commission, performance, users, environment)
                .current(userAuthentication(7L)))
                .isInstanceOf(BizException.class)
                .hasMessage("V_RANK_SANDBOX_USER_REQUIRED");
        verify(users).userScope(7L);
        verifyNoInteractions(commission, performance);
    }

    @Test
    void sandboxRequiresTheSharedEightToNinetySixCharacterRunId() {
        var commission = mock(TeamCommissionRepository.class);
        var performance = mock(VRankPerformanceRepository.class);
        var users = mock(AppTeamInsightsMapper.class);
        when(users.userScope(7L)).thenReturn(new AppTeamInsightsMapper.UserScope(1, "V2"));
        var environment = new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID", "run-123");
        environment.setActiveProfiles("test");

        assertThatThrownBy(() -> new AppVRankController(commission, performance, users, environment)
                .current(userAuthentication(7L)))
                .isInstanceOf(BizException.class)
                .hasMessage("V_RANK_SANDBOX_RUN_ID_REQUIRED");
        verifyNoInteractions(commission, performance);
    }

    @Test
    void unknownOrMixedRuntimeFailsClosedBeforeUserProjection() {
        var commission = mock(TeamCommissionRepository.class);
        var performance = mock(VRankPerformanceRepository.class);
        var users = mock(AppTeamInsightsMapper.class);
        var environment = new MockEnvironment();
        environment.setActiveProfiles("dev", "prod");

        assertThatThrownBy(() -> new AppVRankController(commission, performance, users, environment)
                .current(userAuthentication(7L)))
                .isInstanceOf(BizException.class)
                .hasMessage("V_RANK_PROFILE_INVALID")
                .extracting("code").isEqualTo(503);
        verifyNoInteractions(users, commission, performance);
    }

    @Test
    void productionRejectsSandboxUserBeforeReadingFacts() {
        var commission = mock(TeamCommissionRepository.class);
        var performance = mock(VRankPerformanceRepository.class);
        var users = mock(AppTeamInsightsMapper.class);
        when(users.userScope(7L)).thenReturn(new AppTeamInsightsMapper.UserScope(1, "V2"));

        assertThatThrownBy(() -> new AppVRankController(commission, performance, users, new MockEnvironment())
                .current(userAuthentication(7L)))
                .isInstanceOf(BizException.class)
                .hasMessage("V_RANK_PRODUCTION_USER_REQUIRED");
        verify(users).userScope(7L);
        verifyNoInteractions(commission, performance);
    }

    private Authentication userAuthentication(long userId) {
        var authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn(String.valueOf(userId));
        when(authentication.getDetails()).thenReturn(Map.of("subjectType", "USER"));
        return authentication;
    }
}
