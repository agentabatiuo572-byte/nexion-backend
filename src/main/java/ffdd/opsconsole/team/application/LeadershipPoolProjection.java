package ffdd.opsconsole.team.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/** Read-only estimate using the settlement service's volume, cap and week identity. */
final class LeadershipPoolProjection {
    private LeadershipPoolProjection() { }

    static BigDecimal amount(Map<String, Object> facts, LeadershipPoolConfigGuard.SettlementConfig config) {
        if (value(facts, "weeklySettledCount").signum() > 0) {
            return value(facts, "weeklyInjectedUsd");
        }
        BigDecimal remaining = config.monthlyCap().subtract(value(facts, "monthLeadershipUsd")).max(BigDecimal.ZERO);
        return value(facts, "weeklyGmvUsd").multiply(config.injectRate())
                .setScale(6, RoundingMode.HALF_UP).min(remaining).setScale(6, RoundingMode.DOWN);
    }

    private static BigDecimal value(Map<String, Object> facts, String key) {
        Object value = facts == null ? null : facts.get(key);
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }
}
