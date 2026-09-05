package ffdd.opsconsole.growth.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record AppReferralRewardView(
        String referralCode,
        boolean rewardEnabled,
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
        String runId,
        List<String> factSources,
        Instant refreshedAt) {

    public AppReferralRewardView(
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
            String runId,
            List<String> factSources,
            Instant refreshedAt) {
        this(referralCode, true, inviterRewardNex, invitedCount, pendingCount, settledCount,
                lifetimeInviterNex, walletNexAvailable, recentRewards, limit, source,
                sourceEnvironment, runId, factSources, refreshedAt);
    }

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
