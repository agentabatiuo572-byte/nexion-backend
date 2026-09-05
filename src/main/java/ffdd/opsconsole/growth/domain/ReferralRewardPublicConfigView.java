package ffdd.opsconsole.growth.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** App/H5 登录前可读的 H8 实际发放金额投影。 */
public record ReferralRewardPublicConfigView(
        boolean enabled,
        WelcomeGift welcomeGift,
        InviterReward inviterReward,
        int rhythmMonth,
        BigDecimal newcomerMultiplier,
        BigDecimal inviterMultiplier,
        Instant effectiveAt,
        List<String> sources) {

    public ReferralRewardPublicConfigView(
            WelcomeGift welcomeGift,
            InviterReward inviterReward,
            int rhythmMonth,
            BigDecimal newcomerMultiplier,
            BigDecimal inviterMultiplier,
            Instant effectiveAt,
            List<String> sources) {
        this(true, welcomeGift, inviterReward, rhythmMonth, newcomerMultiplier,
                inviterMultiplier, effectiveAt, sources);
    }

    public record WelcomeGift(
            String lockMode,
            BigDecimal usdtAmount,
            BigDecimal nexAmount) {
    }

    public record InviterReward(BigDecimal nexAmount) {
    }
}
