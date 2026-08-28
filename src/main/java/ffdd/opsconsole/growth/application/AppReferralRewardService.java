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
import org.springframework.core.env.Environment;
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
            "nx_h8_sandbox_referral_ledger");

    private final ReferralRewardMapper mapper;
    private final OpsReferralRewardService rewardConfig;
    private final Clock clock;
    private final Environment environment;

    public ApiResult<AppReferralRewardView> snapshot(Long userId, Integer requestedLimit) {
        if (userId == null || userId <= 0) throw new BizException(403, "USER_SUBJECT_REQUIRED");
        int limit = Math.max(1, Math.min(requestedLimit == null ? 10 : requestedLimit, MAX_LIMIT));
        AppReferralAccount account = mapper.appReferralAccount(userId);
        if (account == null) throw new BizException(503, "H8_REWARD_ACCOUNT_ENVIRONMENT_INCONSISTENT");

        boolean sandbox = account.sandbox() != null && account.sandbox() == 1;
        String[] activeProfiles = environment.getActiveProfiles();
        boolean developmentProfile = activeProfiles != null && activeProfiles.length == 1
                && "dev".equalsIgnoreCase(activeProfiles[0].trim());
        boolean strictSandboxProfile = activeProfiles != null && activeProfiles.length == 1
                && "test".equalsIgnoreCase(activeProfiles[0].trim());
        boolean productionProfile = activeProfiles == null || activeProfiles.length == 0
                || activeProfiles.length == 1 && "prod".equalsIgnoreCase(activeProfiles[0].trim());
        if (!developmentProfile && !strictSandboxProfile && !productionProfile) {
            throw new BizException(503, "H8_REWARD_RUNTIME_PROFILE_UNSUPPORTED");
        }
        if (developmentProfile) requireDevelopmentUser(userId, account.sandbox());
        if (strictSandboxProfile && !sandbox) {
            throw new BizException(403, "H8_SANDBOX_ACCOUNT_REQUIRED");
        }
        if (productionProfile && sandbox) {
            throw new BizException(403, "H8_PRODUCTION_SANDBOX_ACCOUNT_FORBIDDEN");
        }
        boolean sandboxFacts = strictSandboxProfile;
        String runId = sandboxFacts ? requireAcceptanceRunId() : null;
        String environment = sandboxFacts ? "SANDBOX" : "PRODUCTION";
        String sourceType = sandboxFacts ? "MOCK_REFERRAL" : "H8_REFERRAL";
        String source = sandboxFacts ? "mock" : "ledger";
        int accountSandbox = sandbox ? 1 : 0;
        ReferralRewardPublicConfigView config = rewardConfig.publicConfig();
        java.time.LocalDateTime effectiveAt = config.effectiveAt().atZone(ZoneOffset.UTC).toLocalDateTime();
        long invitedCount = sandboxFacts
                ? mapper.appSandboxInvitedCount(userId, effectiveAt, runId)
                : mapper.appInvitedCount(userId, effectiveAt, environment, accountSandbox);
        long pendingCount = sandboxFacts
                ? mapper.appSandboxPendingCount(userId, effectiveAt, runId)
                : mapper.appPendingCount(userId, effectiveAt, environment, accountSandbox);
        long positiveSettlements = sandboxFacts
                ? mapper.appSandboxPositiveSettlementCount(userId, runId)
                : mapper.appPositiveSettlementCount(userId, environment, accountSandbox);
        long settlementCount = sandboxFacts
                ? mapper.appSandboxSettlementCount(userId, runId)
                : mapper.appSettlementCount(userId, environment, accountSandbox);
        AppReferralLedgerSummary summary = sandboxFacts
                ? mapper.appVerifiedSandboxRewardSummary(userId, runId)
                : mapper.appVerifiedRewardSummary(userId, sourceType, environment, accountSandbox);
        long verifiedCount = summary == null || summary.settledCount() == null ? 0 : summary.settledCount();
        if (positiveSettlements != verifiedCount) {
            throw new BizException(503, "H8_REWARD_LEDGER_INCONSISTENT");
        }
        BigDecimal lifetime = summary == null || summary.lifetimeInviterNex() == null
                ? BigDecimal.ZERO : summary.lifetimeInviterNex();
        List<AppReferralLedgerRow> ledgerRows = sandboxFacts
                ? mapper.appRecentVerifiedSandboxRewards(userId, runId, limit)
                : mapper.appRecentVerifiedRewards(userId, sourceType, environment, accountSandbox, limit);
        List<AppReferralRewardView.RewardLedgerItem> rewards = ledgerRows == null
                ? List.of() : ledgerRows.stream().map(this::toView).toList();
        return ApiResult.ok(new AppReferralRewardView(
                account.referralCode(), config.inviterReward().nexAmount(),
                invitedCount, pendingCount, settlementCount, lifetime,
                sandboxFacts ? lifetime : nz(account.walletNexAvailable()), rewards, limit, source, environment, sandboxFacts ? runId : null,
                sandboxFacts ? SANDBOX_FACT_SOURCES : PRODUCTION_FACT_SOURCES, clock.instant()));
    }

    private void requireDevelopmentUser(Long userId, Integer sandbox) {
        if (!Integer.valueOf(1).equals(sandbox)) throw new BizException(403, "H8_DEVELOPMENT_USER_REQUIRED");
        if (userId == null || userId <= 0) throw new BizException(403, "H8_DEVELOPMENT_USER_REQUIRED");
    }

    private String requireAcceptanceRunId() {
        if (!H8AcceptanceSandboxProfileCondition.isStrictIsolatedProfile(environment.getActiveProfiles())) {
            throw new BizException(503, "H8_SANDBOX_PROFILE_REQUIRED");
        }
        String runId = environment.getProperty("NEXION_ACCEPTANCE_RUN_ID");
        if (runId == null || !runId.trim().matches("[A-Za-z0-9][A-Za-z0-9_-]{2,63}")) {
            throw new BizException(503, "H8_SANDBOX_RUN_ID_REQUIRED");
        }
        return runId.trim();
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
