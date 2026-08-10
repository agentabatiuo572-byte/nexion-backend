package ffdd.opsconsole.risk.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;

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

class K4ScoreBackfillInitializerTest {
    @Test
    void malformedActiveModelIsQuarantinedWithoutWritingScoresOrOutboxEvents() {
        RiskOpsRepository repository = mock(RiskOpsRepository.class);
        RiskScoreModelView malformed = new RiskScoreModelView(
                2L, 0L, "active", Map.of(), Map.of(), Map.of(),
                40, 70, 85, "malformed", "system", "system", "now", "now");
        when(repository.activeScoringModel()).thenReturn(Optional.of(malformed));
        EventOutboxService eventOutboxService = mock(EventOutboxService.class);
        K4ScoreBackfillInitializer initializer = new K4ScoreBackfillInitializer(
                repository, new K4RiskScorer(), eventOutboxService, immediateTransactionExecutor());

        initializer.backfillCanonicalScores();

        verify(repository).synchronizeScoringUsers();
        verify(repository, never()).scoreUserNosNeedingProjection(any(Long.class), any(Integer.class));
        verify(repository, never()).refreshScoreProjection(any(), any(Long.class), any(), any(Integer.class), any());
        verify(eventOutboxService, never()).publish(any(), any(), any(), any());
    }

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
                "U00000052", 4, false, 3, false,
                5, new BigDecimal("12000"), 3, 2, true)));
        when(repository.refreshScoreProjection(eq("U00000052"), eq(4L), eq(model), eq(82), any()))
                .thenAnswer(invocation -> Optional.of(new RiskScoreUserView(
                        "U00000052", 82, 82, false, "高风险", "bad", "k4-v1", 5L,
                        "2026-07-22 20:00:00", "刚刚", invocation.getArgument(4))));
        EventOutboxService eventOutboxService = mock(EventOutboxService.class);
        K4ScoreBackfillTransactionExecutor transactionExecutor = immediateTransactionExecutor();

        K4ScoreBackfillInitializer initializer = new K4ScoreBackfillInitializer(
                repository, new K4RiskScorer(), eventOutboxService, transactionExecutor);
        initializer.backfillCanonicalScores();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RiskScoreContributionView>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).refreshScoreProjection(eq("U00000052"), eq(4L), eq(model), eq(82), captor.capture());
        verify(eventOutboxService).publish(
                eq("RISK_SCORE_USER"), eq("U00000052"), eq("risk.score_updated"), any());
        verify(repository).synchronizeScoringUsers();
        assertThat(captor.getValue()).extracting(RiskScoreContributionView::dimKey)
                .containsExactly(
                        "multiAccount", "arbitrage", "withdrawVelocity", "accountAge", "anomalyBehavior");
        assertThat(java.util.Arrays.stream(K4ScoreBackfillInitializer.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(
                        org.springframework.transaction.annotation.Transactional.class)))
                .isEmpty();
        verify(transactionExecutor, times(2)).execute(any());
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
                contribution("multiAccount"), contribution("arbitrage"),
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
                "U00000053", 0, false, 0, false,
                0, BigDecimal.ZERO, 180, 0, false)));
        when(repository.refreshScoreProjection(eq("U00000053"), eq(8L), eq(model), any(Integer.class), any()))
                .thenReturn(Optional.of(canonical));
        K4ScoreBackfillTransactionExecutor transactionExecutor = immediateTransactionExecutor();

        K4ScoreBackfillInitializer initializer = new K4ScoreBackfillInitializer(
                repository, new K4RiskScorer(), mock(EventOutboxService.class), transactionExecutor);
        initializer.backfillCanonicalScores();

        verify(repository).refreshScoreProjection(eq("U00000053"), eq(8L), eq(model), any(Integer.class), any());
    }

    @Test
    void oneUserDeadlockRollsBackThenRetriesInANewBoundedTransaction() {
        RiskOpsRepository repository = mock(RiskOpsRepository.class);
        RiskScoreModelView model = model();
        RiskScoreUserView current = new RiskScoreUserView(
                "U00000054", 10, 10, false, "低风险", "good", "k4-v1", 2L,
                "now", "now", List.of());
        when(repository.activeScoringModel()).thenReturn(Optional.of(model));
        when(repository.scoreUserNosNeedingProjection(1L, K4ScoreBackfillInitializer.CHUNK_SIZE))
                .thenReturn(List.of("U00000054"));
        when(repository.findScoreUser("U00000054")).thenReturn(Optional.of(current));
        when(repository.scoringInput("U00000054")).thenReturn(Optional.of(new RiskScoreRawInput(
                "U00000054", 0, false, 0, false, 0, BigDecimal.ZERO, 180, 0, false)));
        when(repository.refreshScoreProjection(eq("U00000054"), eq(2L), eq(model), any(Integer.class), any()))
                .thenReturn(Optional.of(current));
        K4ScoreBackfillTransactionExecutor transactionExecutor = mock(K4ScoreBackfillTransactionExecutor.class);
        java.util.concurrent.atomic.AtomicInteger executions = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> {
            java.util.function.Supplier<?> attempt = invocation.getArgument(0);
            int execution = executions.incrementAndGet();
            if (execution == 2) {
                throw new org.springframework.dao.DeadlockLoserDataAccessException("retry one user", null);
            }
            return attempt.get();
        }).when(transactionExecutor).execute(any());
        K4ScoreBackfillInitializer initializer = new K4ScoreBackfillInitializer(
                repository, new K4RiskScorer(), mock(EventOutboxService.class), transactionExecutor);

        initializer.backfillCanonicalScores();

        assertThat(executions).hasValue(3);
        verify(repository, times(1)).refreshScoreProjection(
                eq("U00000054"), eq(2L), eq(model), any(Integer.class), any());
    }

    private K4ScoreBackfillTransactionExecutor immediateTransactionExecutor() {
        K4ScoreBackfillTransactionExecutor executor = mock(K4ScoreBackfillTransactionExecutor.class);
        doAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(0)).get())
                .when(executor).execute(any());
        return executor;
    }

    private RiskScoreContributionView contribution(String dimension) {
        return new RiskScoreContributionView(dimension, dimension, false, "none", 0, 0, 0);
    }

    private RiskScoreModelView model() {
        return new RiskScoreModelView(
                1L, 0L, "active",
                Map.of(
                        "multiAccount", 30, "arbitrage", 25,
                        "withdrawVelocity", 20, "accountAge", 10, "anomalyBehavior", 15),
                Map.of(
                        "multiAccount", true, "arbitrage", true,
                        "withdrawVelocity", true, "accountAge", true, "anomalyBehavior", true),
                40, 70, 85, "initial", "system", "system", "now", "now");
    }
}
