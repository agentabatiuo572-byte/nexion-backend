package ffdd.opsconsole.risk.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.risk.domain.RiskOpsRepository;
import ffdd.opsconsole.risk.domain.RiskScoreContributionView;
import ffdd.opsconsole.risk.domain.RiskScoreModelView;
import ffdd.opsconsole.risk.domain.RiskScoreRawInput;
import ffdd.opsconsole.risk.domain.RiskScoreUserView;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;

class K4ScoreBackfillInitializerTest {
    @Test
    void startupBackfillReplacesLegacyContributionsWithCanonicalActiveModelProjection() {
        RiskOpsRepository repository = mock(RiskOpsRepository.class);
        RiskScoreModelView model = model();
        RiskScoreUserView legacy = new RiskScoreUserView(
                "U00000052", 91, 91, false, "高风险", "bad", "v7", 4L,
                "2026-07-15 10:00:00", "2026-07-15 10:00:00",
                List.of(new RiskScoreContributionView("旧维度", "旧证据", 31)));
        when(repository.activeScoringModel()).thenReturn(Optional.of(model));
        when(repository.scoreUserNosNeedingProjection(1L, K4ScoreBackfillInitializer.CHUNK_SIZE))
                .thenReturn(List.of("U00000052"));
        when(repository.findScoreUser("U00000052")).thenReturn(Optional.of(legacy));
        when(repository.scoringInput("U00000052")).thenReturn(Optional.of(new RiskScoreRawInput(
                "U00000052", 4, false, 3, false, "REJECTED",
                5, new BigDecimal("12000"), 3, 2, true)));
        when(repository.recomputeScore(eq("U00000052"), eq(4L), eq(model), eq(83), any()))
                .thenReturn(Optional.of(legacy));

        K4ScoreBackfillInitializer initializer = new K4ScoreBackfillInitializer(
                repository, new K4RiskScorer());
        initializer.backfillCanonicalScores();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RiskScoreContributionView>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).recomputeScore(eq("U00000052"), eq(4L), eq(model), eq(83), captor.capture());
        verify(repository).synchronizeScoringUsers();
        assertThat(captor.getValue()).extracting(RiskScoreContributionView::dimKey)
                .containsExactly(
                        "multiAccount", "arbitrage", "kycStatus",
                        "withdrawVelocity", "accountAge", "anomalyBehavior");
        assertThat(K4ScoreBackfillInitializer.class.getDeclaredMethods())
                .anySatisfy(method -> {
                    if (method.getName().equals("backfillCanonicalScores")) {
                        assertThat(method.isAnnotationPresent(Transactional.class)).isTrue();
                    }
                });
        assertThat(java.util.Arrays.stream(K4ScoreBackfillInitializer.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("synchronizeRuntimeUsers"))
                .findFirst().orElseThrow()
                .isAnnotationPresent(org.springframework.scheduling.annotation.Scheduled.class)).isTrue();
    }

    private RiskScoreModelView model() {
        return new RiskScoreModelView(
                1L, 0L, "active",
                Map.of(
                        "multiAccount", 25, "arbitrage", 20, "kycStatus", 20,
                        "withdrawVelocity", 15, "accountAge", 10, "anomalyBehavior", 10),
                Map.of(
                        "multiAccount", true, "arbitrage", true, "kycStatus", true,
                        "withdrawVelocity", true, "accountAge", true, "anomalyBehavior", true),
                40, 70, 85, "initial", "system", "system", "now", "now");
    }
}
