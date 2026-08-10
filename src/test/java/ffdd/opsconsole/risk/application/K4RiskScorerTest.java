package ffdd.opsconsole.risk.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ffdd.opsconsole.risk.domain.RiskScoreModelView;
import ffdd.opsconsole.risk.domain.RiskScoreRawInput;
import java.math.BigDecimal;
import java.util.Map;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;

class K4RiskScorerTest {
    private final K4RiskScorer scorer = new K4RiskScorer();

    @Test
    void computesAllFiveCanonicalDimensionsDeterministically() {
        var result = scorer.score(
                new RiskScoreRawInput(
                        "U00000052", 4, false, 3, false,
                        5, new BigDecimal("12000"), 3, 2, true),
                model(Map.of(
                        "multiAccount", true, "arbitrage", true,
                        "withdrawVelocity", true, "accountAge", true, "anomalyBehavior", true)));

        assertThat(result.score()).isEqualTo(82);
        assertThat(result.contributions())
                .extracting(contribution -> contribution.dimKey())
                .containsExactly(
                        "multiAccount", "arbitrage", "withdrawVelocity", "accountAge", "anomalyBehavior");
        assertThat(result.contributions()).allSatisfy(contribution -> {
            assertThat(contribution.subScore()).isBetween(0, 100);
            assertThat(contribution.evidence()).isNotBlank();
        });
    }

    @Test
    void disabledInputContributesZeroWithoutRedistributingWeight() {
        var result = scorer.score(
                new RiskScoreRawInput(
                        "U00000052", 0, false, 0, false,
                        0, BigDecimal.ZERO, 400, 0, false),
                model(Map.of(
                        "multiAccount", true, "arbitrage", true,
                        "withdrawVelocity", true, "accountAge", true, "anomalyBehavior", false)));

        assertThat(result.score()).isZero();
        assertThat(result.contributions().stream()
                .filter(value -> value.dimKey().equals("anomalyBehavior"))
                .findFirst().orElseThrow().points()).isZero();
    }

    @Test
    void versionedMappingsDriveWithdrawalBaselineAndLargeActionRules() {
        Map<String, Integer> mappings = new LinkedHashMap<>(K4RiskScorer.DEFAULT_MAPPINGS);
        mappings.put("withdraw.baselineScore", 55);
        mappings.put("withdraw.highScore", 88);
        RiskScoreModelView model = model(Map.of(
                "multiAccount", true, "arbitrage", true,
                "withdrawVelocity", true, "accountAge", true, "anomalyBehavior", true), mappings);

        var baseline = scorer.score(new RiskScoreRawInput(
                "U1", 0, false, 0, false,
                1, new BigDecimal("100"), 14, new BigDecimal("1400"),
                BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("100"),
                200, 0, false), model);
        var high = scorer.score(new RiskScoreRawInput(
                "U2", 0, false, 0, false,
                5, new BigDecimal("9000"), 5, new BigDecimal("9000"),
                BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("5000"),
                3, 0, false), model);

        assertThat(contribution(baseline, "withdrawVelocity").subScore()).isEqualTo(55);
        assertThat(contribution(high, "withdrawVelocity").subScore()).isEqualTo(88);
        assertThat(contribution(high, "accountAge").subScore()).isEqualTo(70);
    }

    @Test
    void malformedActiveModelCannotSilentlyProduceZeroOrDefaultScores() {
        RiskScoreModelView malformed = new RiskScoreModelView(
                2L, 0L, "active", Map.of(), Map.of(), Map.of(),
                40, 70, 85, "malformed", "system", "system", "now", "now");

        assertThatThrownBy(() -> scorer.score(
                new RiskScoreRawInput(
                        "U00000052", 4, false, 3, false,
                        5, new BigDecimal("12000"), 3, 2, true),
                malformed))
                .hasMessage("K4_MODEL_SNAPSHOT_INVALID");
    }

    private RiskScoreModelView model(Map<String, Boolean> sources) {
        return model(sources, K4RiskScorer.DEFAULT_MAPPINGS);
    }

    private RiskScoreModelView model(Map<String, Boolean> sources, Map<String, Integer> mappings) {
        return new RiskScoreModelView(
                1L, 0L, "active",
                Map.of(
                        "multiAccount", 30, "arbitrage", 25,
                        "withdrawVelocity", 20, "accountAge", 10, "anomalyBehavior", 15),
                sources, mappings, 40, 70, 85, "initial model", "system", "system", "now", "now");
    }

    private ffdd.opsconsole.risk.domain.RiskScoreContributionView contribution(
            K4RiskScorer.ScoreResult result, String key) {
        return result.contributions().stream().filter(value -> value.dimKey().equals(key)).findFirst().orElseThrow();
    }
}
