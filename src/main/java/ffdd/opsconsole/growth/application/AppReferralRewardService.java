package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.growth.domain.AppReferralRewardView;
import ffdd.opsconsole.growth.domain.ReferralRewardPublicConfigView;
import ffdd.opsconsole.growth.mapper.ReferralRewardMapper;
import ffdd.opsconsole.growth.mapper.ReferralRewardMapper.AppReferralAccount;
import ffdd.opsconsole.growth.mapper.ReferralRewardMapper.AppReferralLedgerRow;
import ffdd.opsconsole.growth.mapper.ReferralRewardMapper.AppReferralLedgerSummary;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AppReferralRewardService {
    public static final int MAX_LIMIT = 20;
    private static final List<String> PRODUCTION_FACT_SOURCES = List.of(
            "nx_referral_reward_settlement",
            "nx_wallet_ledger",
            "nx_earnings_release_entry",
            "nx_user_wallet");
    private static final List<String> SANDBOX_FACT_SOURCES = List.of(
            "nx_h8_sandbox_referral_settlement",
            "nx_h8_sandbox_referral_ledger",
            "nx_user_wallet");

    private final ReferralRewardMapper mapper;
    private final OpsReferralRewardService rewardConfig;
    private final Clock clock;

    public ApiResult<AppReferralRewardView> snapshot(Long userId, Integer requestedLimit) {
        if (userId == null || userId <= 0) throw new BizException(403, "USER_SUBJECT_REQUIRED");
        int limit = Math.max(1, Math.min(requestedLimit == null ? 10 : requestedLimit, MAX_LIMIT));
        AppReferralAccount account = mapper.appReferralAccount(userId);
        if (account == null) throw new BizException(503, "H8_REWARD_ACCOUNT_ENVIRONMENT_INCONSISTENT");

        boolean sandbox = account.sandbox() != null && account.sandbox() == 1;
        String environment = sandbox ? "SANDBOX" : "PRODUCTION";
        String sourceType = sandbox ? "MOCK_REFERRAL" : "H8_REFERRAL";
        String source = sandbox ? "mock" : "ledger";
        ReferralRewardPublicConfigView config = rewardConfig.publicConfig();
        java.time.LocalDateTime effectiveAt = config.effectiveAt().atZone(ZoneOffset.UTC).toLocalDateTime();
        long invitedCount = mapper.appInvitedCount(userId, effectiveAt, environment);
        long pendingCount = sandbox
                ? mapper.appSandboxPendingCount(userId, effectiveAt)
                : mapper.appPendingCount(userId, effectiveAt, environment);
        long positiveSettlements = sandbox
                ? mapper.appSandboxPositiveSettlementCount(userId)
                : mapper.appPositiveSettlementCount(userId, environment);
        long settlementCount = sandbox
                ? mapper.appSandboxSettlementCount(userId)
                : mapper.appSettlementCount(userId, environment);
        AppReferralLedgerSummary summary = sandbox
                ? mapper.appVerifiedSandboxRewardSummary(userId)
                : mapper.appVerifiedRewardSummary(userId, sourceType, environment);
        long verifiedCount = summary == null || summary.settledCount() == null ? 0 : summary.settledCount();
        if (positiveSettlements != verifiedCount) {
            throw new BizException(503, "H8_REWARD_LEDGER_INCONSISTENT");
        }
        BigDecimal lifetime = summary == null || summary.lifetimeInviterNex() == null
                ? BigDecimal.ZERO : summary.lifetimeInviterNex();
        List<AppReferralLedgerRow> ledgerRows = sandbox
                ? mapper.appRecentVerifiedSandboxRewards(userId, limit)
                : mapper.appRecentVerifiedRewards(userId, sourceType, environment, limit);
        List<AppReferralRewardView.RewardLedgerItem> rewards = ledgerRows == null
                ? List.of() : ledgerRows.stream().map(this::toView).toList();
        return ApiResult.ok(new AppReferralRewardView(
                account.referralCode(), config.inviterReward().nexAmount(),
                invitedCount, pendingCount, settlementCount, lifetime,
                nz(account.walletNexAvailable()), rewards, limit, source, environment,
                sandbox ? SANDBOX_FACT_SOURCES : PRODUCTION_FACT_SOURCES, clock.instant()));
    }

    private AppReferralRewardView.RewardLedgerItem toView(AppReferralLedgerRow row) {
        return new AppReferralRewardView.RewardLedgerItem(
                row.settlementNo(), row.amountNex(), row.ledgerStatus(), row.balanceAfter(),
                row.releaseBucket(), row.sourceEnvironment(),
                row.settledAt().atZone(clock.getZone()).toInstant());
    }

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
