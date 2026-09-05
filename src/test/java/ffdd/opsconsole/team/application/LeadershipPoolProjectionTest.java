package ffdd.opsconsole.team.application;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LeadershipPoolProjectionTest {
    private final LeadershipPoolConfigGuard.SettlementConfig config =
            new LeadershipPoolConfigGuard.SettlementConfig(1L, new BigDecimal("0.05"), 3,
                    new BigDecimal("1000"), "0 0 0 * * *", "test");

    @Test void previewsCurrentWeekVolumeWithinRemainingMonthlyCap() {
        assertThat(LeadershipPoolProjection.amount(Map.of("weeklyGmvUsd", 10000,
                "monthLeadershipUsd", 0), config)).isEqualByComparingTo("500");
        assertThat(LeadershipPoolProjection.amount(Map.of("weeklyGmvUsd", 10000,
                "monthLeadershipUsd", 900), config)).isEqualByComparingTo("100");
        assertThat(LeadershipPoolProjection.amount(Map.of("weeklyGmvUsd", 10000,
                "monthLeadershipUsd", 1100), config)).isEqualByComparingTo("0");
    }

    @Test void alreadySettledWeekKeepsItsActualAmountEvenWhenMonthlyCapIsExhausted() {
        assertThat(LeadershipPoolProjection.amount(Map.of("weeklyGmvUsd", 10000,
                "monthLeadershipUsd", 1000, "weeklySettledCount", 2,
                "weeklyInjectedUsd", new BigDecimal("321.123456")), config))
                .isEqualByComparingTo("321.123456");
    }
}
