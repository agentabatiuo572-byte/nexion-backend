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
import ffdd.opsconsole.shared.outbox.EventOutboxService;
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
        when(repository.refreshScoreProjection(eq("U00000052"), eq(4L), eq(model), eq(83), any()))
                .thenAnswer(invocation -> Optional.of(new RiskScoreUserView(
                        "U00000052", 83, 83, false, "高风险", "bad", "k4-v1", 5L,
                        "2026-07-22 20:00:00", "刚刚", invocation.getArgument(4))));
        EventOutboxService eventOutboxService = mock(EventOutboxService.class);
        K4KycReviewTriggerService triggerService = mock(K4KycReviewTriggerService.class);

        K4ScoreBackfillInitializer initializer = new K4ScoreBackfillInitializer(
                repository, new K4RiskScorer(), eventOutboxService, triggerService);
        initializer.backfillCanonicalScores();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RiskScoreContributionView>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).refreshScoreProjection(eq("U00000052"), eq(4L), eq(model), eq(83), captor.capture());
        verify(eventOutboxService).publish(
                eq("RISK_SCORE_USER"), eq("U00000052"), eq("risk.score_updated"), any());
        verify(triggerService).triggerIfThresholdReached(
                eq(legacy),
                org.mockito.ArgumentMatchers.argThat(user -> "U00000052".equals(user.userNo())
                        && user.effectiveScore() == 83),
                eq(K4KycReviewTriggerService.SOURCE_FACT_REFRESH),
                eq("K4 source facts or stale projection required recompute"),
                eq("system"),
                eq("k4-fact-refresh:1:U00000052:5"));
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

    @Test
    void runtimeRefreshRecomputesASelectedCanonicalProjectionWhenItsSourceFactsAreNewer() {
        RiskOpsRepository repository = mock(RiskOpsRepository.class);
        RiskScoreModelView model = model();
        List<RiskScoreContributionView> canonicalRows = List.of(
                contribution("multiAccount"), contribution("arbitrage"), contribution("kycStatus"),
                contribution("withdrawVelocity"), contribution("accountAge"), contribution("anomalyBehavior"));
        RiskScoreUserView canonical = new RiskScoreUserView(
                "U00000053", 0, 0, false, "低风险", "good", "k4-v1", 8L,
                "2026-07-22 19:00:00", "1小时前", canonicalRows);
        when(repository.activeScoringModel()).thenReturn(Optional.of(model));
        // The repository selects this structurally current row because a canonical source fact is newer than as_of.
        when(repository.scoreUserNosNeedingProjection(1L, K4ScoreBackfillInitializer.CHUNK_SIZE))
                .thenReturn(List.of("U00000053"));
        when(repository.findScoreUser("U00000053")).thenReturn(Optional.of(canonical));
        when(repository.scoringInput("U00000053")).thenReturn(Optional.of(new RiskScoreRawInput(
                "U00000053", 0, false, 0, false, "PASSED",
                0, BigDecimal.ZERO, 180, 0, false)));
        when(repository.refreshScoreProjection(eq("U00000053"), eq(8L), eq(model), any(Integer.class), any()))
                .thenReturn(Optional.of(canonical));
        K4KycReviewTriggerService triggerService = mock(K4KycReviewTriggerService.class);

        K4ScoreBackfillInitializer initializer = new K4ScoreBackfillInitializer(
                repository, new K4RiskScorer(), mock(EventOutboxService.class), triggerService);
        initializer.backfillCanonicalScores();

        verify(repository).refreshScoreProjection(eq("U00000053"), eq(8L), eq(model), any(Integer.class), any());
        verify(triggerService).triggerIfThresholdReached(
                eq(canonical), eq(canonical), eq(K4KycReviewTriggerService.SOURCE_FACT_REFRESH),
                eq("K4 source facts or stale projection required recompute"), eq("system"),
                eq("k4-fact-refresh:1:U00000053:8"));
    }

    private RiskScoreContributionView contribution(String dimension) {
        return new RiskScoreContributionView(dimension, dimension, false, "none", 0, 0, 0);
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
