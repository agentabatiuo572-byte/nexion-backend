package ffdd.opsconsole.risk.application;

import ffdd.opsconsole.risk.domain.RiskOpsRepository;
import ffdd.opsconsole.risk.domain.RiskScoreModelView;
import ffdd.opsconsole.risk.domain.RiskScoreRawInput;
import ffdd.opsconsole.risk.domain.RiskScoreUserView;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
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
    private final K4KycReviewTriggerService kycReviewTriggerService;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        backfillCanonicalScores();
    }

    @Transactional
    public synchronized void backfillCanonicalScores() {
        riskRepository.synchronizeScoringUsers();
        RiskScoreModelView model = riskRepository.activeScoringModel()
                .orElseThrow(() -> new BizException(500, "K4_ACTIVE_MODEL_REQUIRED"));
        for (String userNo : riskRepository.scoreUserNosNeedingProjection(model.version(), CHUNK_SIZE)) {
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
            kycReviewTriggerService.triggerIfThresholdReached(
                    current,
                    updated,
                    K4KycReviewTriggerService.SOURCE_FACT_REFRESH,
                    "K4 source facts or stale projection required recompute",
                    "system",
                    "k4-fact-refresh:" + model.version() + ":" + userNo + ":" + updated.rowVersion());
        }
    }

    /** Keeps newly registered and retired users aligned without requiring a service restart. */
    @Scheduled(fixedDelayString = "${nexion.risk.k4.sync-delay-ms:1000}")
    @Transactional
    public void synchronizeRuntimeUsers() {
        backfillCanonicalScores();
    }

}
