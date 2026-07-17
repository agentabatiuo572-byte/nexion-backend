package ffdd.opsconsole.risk.application;

import ffdd.opsconsole.risk.domain.RiskOpsRepository;
import ffdd.opsconsole.risk.domain.RiskScoreContributionView;
import ffdd.opsconsole.risk.domain.RiskScoreModelView;
import ffdd.opsconsole.risk.domain.RiskScoreRawInput;
import ffdd.opsconsole.risk.domain.RiskScoreUserView;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.List;
import java.util.Set;
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
    static final int CHUNK_SIZE = 200;
    private static final Set<String> CANONICAL_DIMENSIONS = Set.of(
            "multiAccount", "arbitrage", "kycStatus",
            "withdrawVelocity", "accountAge", "anomalyBehavior");

    private final RiskOpsRepository riskRepository;
    private final K4RiskScorer scorer;

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
            if (isCurrentProjection(current, model)) continue;
            RiskScoreRawInput input = riskRepository.scoringInput(userNo)
                    .orElseThrow(() -> new BizException(500, "K4_SCORE_INPUT_MISSING_DURING_BACKFILL"));
            K4RiskScorer.ScoreResult result = scorer.score(input, model);
            if (riskRepository.recomputeScore(
                    userNo, current.rowVersion(), model, result.score(), result.contributions()).isEmpty()) {
                throw new BizException(409, "K4_SCORE_CONCURRENT_UPDATE_DURING_BACKFILL");
            }
        }
    }

    /** Keeps newly registered and retired users aligned without requiring a service restart. */
    @Scheduled(fixedDelayString = "${nexion.risk.k4.sync-delay-ms:1000}")
    @Transactional
    public void synchronizeRuntimeUsers() {
        backfillCanonicalScores();
    }

    private boolean isCurrentProjection(RiskScoreUserView score, RiskScoreModelView model) {
        List<RiskScoreContributionView> rows = score.contributions();
        if (!java.util.Objects.equals(score.modelVersion(), "k4-v" + model.version())
                || rows == null || rows.size() != CANONICAL_DIMENSIONS.size()) {
            return false;
        }
        Set<String> keys = rows.stream().map(RiskScoreContributionView::dimKey)
                .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        int points = rows.stream().map(RiskScoreContributionView::points)
                .filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).sum();
        return keys.equals(CANONICAL_DIMENSIONS)
                && java.util.Objects.equals(score.modelScore(), points);
    }
}
