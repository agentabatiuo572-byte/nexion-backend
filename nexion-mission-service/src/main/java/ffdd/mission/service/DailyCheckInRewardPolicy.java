package ffdd.mission.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

@Component
public class DailyCheckInRewardPolicy {
    private static final BigDecimal MULTIPLIER_NORMAL = new BigDecimal("1.00");
    private static final BigDecimal MULTIPLIER_LUCKY = new BigDecimal("1.50");
    private static final BigDecimal MULTIPLIER_SUPER_LUCKY = new BigDecimal("2.00");

    public DailyCheckInReward roll(int basePoints) {
        BigDecimal multiplier = rollMultiplier();
        int awardedPoints = multiplier.multiply(BigDecimal.valueOf(Math.max(0, basePoints)))
                .setScale(0, RoundingMode.HALF_UP)
                .intValue();
        return new DailyCheckInReward(Math.max(0, basePoints), multiplier, awardedPoints);
    }

    private BigDecimal rollMultiplier() {
        int roll = ThreadLocalRandom.current().nextInt(100);
        if (roll < 5) {
            return MULTIPLIER_SUPER_LUCKY;
        }
        if (roll < 20) {
            return MULTIPLIER_LUCKY;
        }
        return MULTIPLIER_NORMAL;
    }
}
