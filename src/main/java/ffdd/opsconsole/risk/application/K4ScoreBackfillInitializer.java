package ffdd.opsconsole.risk.application;

import ffdd.opsconsole.risk.domain.RiskOpsRepository;
import ffdd.opsconsole.risk.domain.RiskScoreModelView;
import ffdd.opsconsole.risk.domain.RiskScoreRawInput;
import ffdd.opsconsole.risk.domain.RiskScoreUserView;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

/** Rebuilds legacy K4 score projections before the application reports startup complete. */
@Component
@Order(20)
@RequiredArgsConstructor
public class K4ScoreBackfillInitializer implements ApplicationRunner {
    public static final int CHUNK_SIZE = 200;

    private final RiskOpsRepository riskRepository;
    private final K4RiskScorer scorer;
    private final EventOutboxService eventOutboxService;
    private final K4ScoreBackfillTransactionExecutor transactionExecutor;

    @Override
    public void run(ApplicationArguments args) {
        backfillCanonicalScores();
    }

    public synchronized void backfillCanonicalScores() {
        BackfillBatch batch = transactionExecutor.execute(() -> {
            riskRepository.synchronizeScoringUsers();
            RiskScoreModelView model = riskRepository.activeScoringModel()
                    .orElseThrow(() -> new BizException(500, "K4_ACTIVE_MODEL_REQUIRED"));
            return new BackfillBatch(
                    model,
                    riskRepository.scoreUserNosNeedingProjection(model.version(), CHUNK_SIZE));
        });
        for (String userNo : batch.userNos()) {
            executeProjectionWithBoundedRetry(batch.model(), userNo);
        }
    }

    private void executeProjectionWithBoundedRetry(RiskScoreModelView model, String userNo) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                transactionExecutor.execute(() -> {
                    projectOneUser(model, userNo);
                    return null;
                });
                return;
            } catch (PessimisticLockingFailureException transientLockFailure) {
                if (attempt == 2) {
                    throw transientLockFailure;
                }
            }
        }
    }

    private void projectOneUser(RiskScoreModelView model, String userNo) {
        RiskScoreUserView current = riskRepository.findScoreUser(userNo)
                .orElseThrow(() -> new BizException(500, "K4_SCORE_USER_MISSING_DURING_BACKFILL"));
        RiskScoreRawInput input = riskRepository.scoringInput(userNo)
                .orElseThrow(() -> new BizException(500, "K4_SCORE_INPUT_MISSING_DURING_BACKFILL"));
        K4RiskScorer.ScoreResult result = scorer.score(input, model);
        RiskScoreUserView updated = riskRepository.refreshScoreProjection(
                userNo, current.rowVersion(), model, result.score(), result.contributions()).orElse(null);
        if (updated == null) {
            throw new BizException(409, "K4_SCORE_CONCURRENT_UPDATE_DURING_BACKFILL");
        }
        K4ScoreEventPublisher.publishScoreUpdated(eventOutboxService, current, updated);
    }

    /** Keeps newly registered and retired users aligned without requiring a service restart. */
    @Scheduled(fixedDelayString = "${nexion.risk.k4.sync-delay-ms:1000}")
    public void synchronizeRuntimeUsers() {
        backfillCanonicalScores();
    }

    private record BackfillBatch(RiskScoreModelView model, java.util.List<String> userNos) {
    }

}
