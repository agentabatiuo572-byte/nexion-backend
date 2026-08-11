package ffdd.opsconsole.growth.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record AppReferralRewardView(
        String referralCode,
        BigDecimal inviterRewardNex,
        long invitedCount,
        long pendingCount,
        long settledCount,
        BigDecimal lifetimeInviterNex,
        BigDecimal walletNexAvailable,
        List<RewardLedgerItem> recentRewards,
        int limit,
        String source,
        String sourceEnvironment,
        List<String> factSources,
        Instant refreshedAt) {

    public record RewardLedgerItem(
            String settlementNo,
            BigDecimal amountNex,
            String ledgerStatus,
            BigDecimal balanceAfter,
            String releaseBucket,
            String sourceEnvironment,
            Instant settledAt) {
    }
}
