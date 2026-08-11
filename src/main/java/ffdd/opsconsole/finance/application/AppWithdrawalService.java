package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.mapper.AppWithdrawalMapper;
import ffdd.opsconsole.finance.mapper.AppWithdrawalMapper.Attribution;
import ffdd.opsconsole.finance.mapper.AppWithdrawalMapper.PayoutAddressRow;
import ffdd.opsconsole.finance.mapper.AppWithdrawalMapper.WalletRow;
import ffdd.opsconsole.finance.mapper.AppWithdrawalMapper.WithdrawalRiskFacts;
import ffdd.opsconsole.finance.mapper.AppWithdrawalMapper.WithdrawalWrite;
import ffdd.opsconsole.growth.facade.GrowthRhythmFacade;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.risk.facade.WithdrawalRiskContext;
import ffdd.opsconsole.risk.facade.WithdrawalRiskDecision;
import ffdd.opsconsole.risk.facade.WithdrawalRiskRuleFacade;
import ffdd.opsconsole.emergency.domain.KillSwitchState;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** D2/D5 user entry point; fixed D5 fees and the accepted policy version are snapshotted at submission. */
@Service
@RequiredArgsConstructor
public class AppWithdrawalService {
    private static final Set<String> CHAINS = Set.of("USDT-TRC20", "USDT-BEP20", "USDT-ERC20");
    private static final BigDecimal MIN_WITHDRAWAL = new BigDecimal("20.000000");
    private static final String WITHDRAW_KILLSWITCH_KEY = "killswitch.withdraw";
    private static final String WITHDRAW_LEGACY_KILLSWITCH_KEY = "emergency.killswitch.withdraw";
    private static final String STRONG_REVIEW_THRESHOLD_KEY = "withdrawal.strong_review_threshold_usdt";

    private final AppWithdrawalMapper mapper;
    private final PlatformConfigFacade config;
    private final GrowthRhythmFacade growthRhythmFacade;
    private final AdminIdempotencyService idempotency;
    private final AuditLogService audit;
    private final EventOutboxService outbox;
    private final WithdrawalRiskRuleFacade withdrawalRiskRuleFacade;
    private final TreasuryLedgerPostingFacade ledgerPostingFacade;
    private final EarningsReleaseService earningsReleaseService;

    public ApiResult<Map<String, Object>> list(Long userId) {
        if (userId == null || mapper.findActiveUser(userId) == null) throw new BizException(404, "USER_NOT_FOUND");
        return ApiResult.ok(linked("withdrawals", mapper.userWithdrawals(userId, 50),
                "source", "nx_withdrawal_order"));
    }

    public ApiResult<Map<String, Object>> policy(Long userId) {
        if (userId == null || mapper.findActiveUser(userId) == null) throw new BizException(404, "USER_NOT_FOUND");
        PolicySnapshot policy = currentPolicy();
        int dailyLimit = requiredDecimal("withdrawal.daily_count_limit").intValueExact();
        BigDecimal balanceMaxRatio = requiredDecimal("withdrawal.max_balance_pct");
        GrowthRhythmSnapshot rhythm = growthRhythmFacade.snapshot();
        if (dailyLimit < 1 || dailyLimit > 10
                || balanceMaxRatio.signum() <= 0 || balanceMaxRatio.compareTo(BigDecimal.ONE) > 0
                || rhythm == null || !StringUtils.hasText(rhythm.currentPhase()) || rhythm.currentMonth() < 1
                || rhythm.withdrawCooldownDays() <= 0) {
            throw new BizException(503, "WITHDRAWAL_POLICY_UNAVAILABLE");
        }
        boolean withdrawalEnabled = withdrawGateEnabled();
        BigDecimal strongReviewThreshold = strongReviewThreshold();
        return ApiResult.ok(linked(
                "minAmount", MIN_WITHDRAWAL, "dailyLimitCount", dailyLimit,
                "balanceMaxRatio", balanceMaxRatio,
                "networkConfirmFeeUsd", policy.networkConfirmFeeUsd(),
                "nexFeeOffsetRate", policy.nexFeeOffsetRate(),
                "smallAmountThresholdUsd", policy.smallAmountThresholdUsd(),
                "strongReviewThresholdUsdt", strongReviewThreshold,
                "payoutSlaHours", policy.payoutSlaHours(),
                "policyVersion", policy.policyVersion(),
                "cooldownDays", rhythm.withdrawCooldownDays(),
                "complianceHoldEnabled", rhythm.complianceHoldEnabled(),
                "enabledNetworks", policy.enabledNetworks(), "currentPhase", rhythm.currentPhase(),
                "currentMonth", rhythm.currentMonth(), "withdrawalEnabled", withdrawalEnabled,
                "gateSource", "J1", "source", "D5+H1"));
    }

    @Transactional(rollbackFor = Exception.class)
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Deprecated
    public ApiResult<Map<String, Object>> submit(
            Long userId, BigDecimal amount, String chain, String address, String idempotencyKey) {
        // Compatibility callers never expressed an offset intent; defaulting them
        // to true would silently burn NEX. Only the explicit D5 request may opt in.
        return submit(userId, amount, chain, address, currentPolicy().policyVersion(), false, idempotencyKey);
    }

    @Transactional(rollbackFor = Exception.class)
    @SuppressWarnings({"rawtypes", "unchecked"})
    public ApiResult<Map<String, Object>> submit(
            Long userId, BigDecimal amount, String chain, String address, String policyVersion,
            boolean useNexFeeOffset, String idempotencyKey) {
        if (userId == null || mapper.lockActiveUser(userId) == null) throw new BizException(404, "USER_NOT_FOUND");
        BigDecimal normalizedAmount = money(amount);
        String normalizedChain = normalizeChain(chain);
        String normalizedAddress = normalizeAddress(address);
        String requestHash = hash(userId + "|" + normalizedAmount + "|" + normalizedChain + "|"
                + normalizedAddress + "|" + policyVersion + "|" + useNexFeeOffset);
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotency.execute(
                "USER_WITHDRAWAL_SUBMIT", requireKey(idempotencyKey), requestHash, ApiResult.class,
                () -> submitOnce(userId, normalizedAmount, normalizedChain, normalizedAddress,
                        policyVersion, useNexFeeOffset));
    }

    private ApiResult<Map<String, Object>> submitOnce(
            Long userId, BigDecimal amount, String chain, String address, String requestedPolicyVersion,
            boolean useNexFeeOffset) {
        if (!withdrawGateEnabled()) {
            return ApiResult.fail(409, "WITHDRAWAL_KILL_SWITCH_DISABLED");
        }
        PolicySnapshot policy = currentPolicy();
        PayoutAddressRow payoutAddress = mapper.lockPayoutAddress(userId, chain);
        if (payoutAddress == null || !StringUtils.hasText(payoutAddress.address())) {
            return ApiResult.fail(409, "WITHDRAWAL_PAYOUT_ADDRESS_REQUIRED");
        }
        if (payoutAddress.effectiveAt() == null || payoutAddress.effectiveAt().isAfter(LocalDateTime.now())) {
            return ApiResult.fail(409, "WITHDRAWAL_PAYOUT_ADDRESS_CHANGE_PENDING");
        }
        if (!payoutAddressMatches(chain, address, payoutAddress.address())) {
            return ApiResult.fail(409, "WITHDRAWAL_PAYOUT_ADDRESS_MISMATCH");
        }
        if (!StringUtils.hasText(requestedPolicyVersion)
                || !policy.policyVersion().equals(requestedPolicyVersion.trim())) {
            return ApiResult.fail(409, "WITHDRAWAL_POLICY_VERSION_CONFLICT",
                    Map.of("policyVersion", policy.policyVersion()));
        }
        if (!policy.enabledNetworks().contains(chain)) return ApiResult.fail(409, "WITHDRAWAL_NETWORK_DISABLED");

        int dailyLimit = requiredDecimal("withdrawal.daily_count_limit").intValueExact();
        if (dailyLimit < 1 || dailyLimit > 10) throw new BizException(503, "D5_DAILY_LIMIT_INVALID");
        if (mapper.countLast24Hours(userId) >= dailyLimit) return ApiResult.fail(409, "WITHDRAWAL_DAILY_LIMIT_EXCEEDED");

        WalletRow wallet = mapper.lockWallet(userId);
        if (wallet == null || wallet.version() == null) throw new BizException(409, "WITHDRAWAL_WALLET_UNAVAILABLE");
        BigDecimal maxRatio = requiredDecimal("withdrawal.max_balance_pct");
        if (maxRatio.signum() <= 0 || maxRatio.compareTo(BigDecimal.ONE) > 0) {
            throw new BizException(503, "D5_BALANCE_RATIO_INVALID");
        }
        BigDecimal maxAmount = safe(wallet.usdtAvailable()).multiply(maxRatio).setScale(6, RoundingMode.DOWN);
        if (amount.compareTo(MIN_WITHDRAWAL) < 0) return ApiResult.fail(422, "WITHDRAWAL_MIN_AMOUNT_NOT_MET");
        if (amount.compareTo(maxAmount) > 0 || amount.compareTo(safe(wallet.usdtAvailable())) > 0) {
            return ApiResult.fail(409, "WITHDRAWAL_BALANCE_LIMIT_EXCEEDED");
        }
        if (earningsReleaseService != null) {
            earningsReleaseService.assertWithdrawable(userId, safe(wallet.usdtAvailable()), amount);
        }

        GrowthRhythmSnapshot rhythm = growthRhythmFacade.snapshot();
        if (rhythm == null || rhythm.currentMonth() <= 0 || rhythm.withdrawCooldownDays() <= 0) {
            throw new BizException(503, "H1_WITHDRAWAL_DIAL_UNAVAILABLE");
        }
        BigDecimal networkFee = policy.networkConfirmFeeUsd().get(networkKey(chain)).setScale(6, RoundingMode.UNNECESSARY);
        BigDecimal penaltyPct = BigDecimal.ZERO.setScale(6);
        BigDecimal penaltyFee = BigDecimal.ZERO.setScale(6);
        BigDecimal grossFee = networkFee;
        BigDecimal nexOffsetRate = policy.nexFeeOffsetRate();
        BigDecimal requiredNex = useNexFeeOffset
                ? networkFee.divide(nexOffsetRate, 0, RoundingMode.CEILING).setScale(6)
                : BigDecimal.ZERO.setScale(6);
        BigDecimal nexBurned = useNexFeeOffset
                ? safe(wallet.nexAvailable()).min(requiredNex).setScale(6, RoundingMode.DOWN)
                : BigDecimal.ZERO.setScale(6);
        BigDecimal feeWaived = useNexFeeOffset
                ? nexBurned.multiply(nexOffsetRate).min(networkFee).setScale(6, RoundingMode.DOWN)
                : BigDecimal.ZERO.setScale(6);
        BigDecimal actualFee = grossFee.subtract(feeWaived).max(BigDecimal.ZERO).setScale(6, RoundingMode.DOWN);
        BigDecimal penaltyFeeWaived = BigDecimal.ZERO.setScale(6);
        BigDecimal networkFeeWaived = feeWaived;
        BigDecimal actualPenaltyFee = BigDecimal.ZERO.setScale(6);
        BigDecimal actualNetworkFee = actualFee;
        if (actualNetworkFee.add(actualPenaltyFee).compareTo(actualFee) != 0) {
            throw new BizException(503, "D5_FEE_COMPONENT_INVARIANT_BROKEN");
        }
        BigDecimal netReceive = amount.subtract(actualFee).setScale(6, RoundingMode.DOWN);
        if (netReceive.signum() <= 0) return ApiResult.fail(422, "WITHDRAWAL_NET_AMOUNT_INVALID");

        String withdrawalNo = "WD-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        LocalDateTime holdUntil = LocalDateTime.now().plusDays(rhythm.withdrawCooldownDays());
        WithdrawalRiskFacts riskFacts = mapper.withdrawalRiskFacts(userId, address);
        if (riskFacts == null || !StringUtils.hasText(riskFacts.userNo())
                || riskFacts.withdrawalCount24h() == null || riskFacts.withdrawalSum24h() == null
                || riskFacts.accountAgeDays() == null || !StringUtils.hasText(riskFacts.addressReputation())) {
            throw new BizException(503, "K3_WITHDRAWAL_FACTS_UNAVAILABLE");
        }
        if (riskFacts.k4RiskScore() == null
                || !StringUtils.hasText(riskFacts.k4ModelVersion())
                || !riskFacts.k4ModelVersion().matches("k4-v\\d+")
                || riskFacts.k4AsOf() == null
                || riskFacts.k4AsOf().isBefore(LocalDateTime.now().minusDays(1))
                || !validK4Thresholds(riskFacts)) {
            throw new BizException(503, "K4_RISK_SCORE_UNAVAILABLE");
        }
        WithdrawalRiskContext riskContext = new WithdrawalRiskContext(
                userId, withdrawalNo, riskFacts.userNo(), amount,
                riskFacts.withdrawalCount24h() + 1,
                riskFacts.withdrawalSum24h().add(amount),
                riskFacts.accountAgeDays(), riskFacts.addressReputation(),
                chain, address, null);
        WithdrawalRiskDecision riskDecision;
        try {
            riskDecision = withdrawalRiskRuleFacade.evaluate(riskContext);
        } catch (RuntimeException unavailable) {
            throw new BizException(503, "K3_WITHDRAWAL_DECISION_UNAVAILABLE");
        }
        if (riskDecision == null || !Set.of("pass", "delay", "manual", "freeze").contains(riskDecision.action())) {
            throw new BizException(503, "K3_WITHDRAWAL_DECISION_UNAVAILABLE");
        }
        int k4Score = riskFacts.k4RiskScore();
        String k4Priority = k4Priority(k4Score, riskFacts);
        boolean frozen = "freeze".equals(riskDecision.action());
        boolean delayed = "delay".equals(riskDecision.action());
        BigDecimal strongReviewThreshold = strongReviewThreshold();
        boolean strongReview = amount.compareTo(strongReviewThreshold) >= 0;
        boolean smallAmountEligible = amount.compareTo(policy.smallAmountThresholdUsd()) <= 0;
        boolean fastTrack = !strongReview && k4Score < riskFacts.k4BandLowMax()
                && "pass".equals(riskDecision.action());
        String riskRoute = strongReview ? "strong-review" : finalRiskRoute(k4Score, riskFacts, riskDecision);
        // H1 cooldown remains authoritative: low-risk fast-track is auto-reviewed only after the hold expires.
        String status = frozen ? "FROZEN" : strongReview ? "REVIEW_PENDING"
                : (fastTrack || delayed || "fast-pass".equals(riskRoute)) ? "EXTENDED_HOLD" : "REVIEW_PENDING";
        String failureReason = strongReview ? "A3_STRONG_REVIEW_THRESHOLD"
                : fastTrack ? "H1_COOLDOWN_FAST_TRACK"
                : riskRouteEvidence(k4Score, riskFacts, riskDecision);
        LocalDateTime payoutDueAt = holdUntil.plusHours(policy.payoutSlaHours());
        if (mapper.reserveFunds(userId, amount, nexBurned, wallet.version()) != 1) {
            throw new BizException(409, "WITHDRAWAL_WALLET_CONFLICT");
        }
        WithdrawalWrite write = new WithdrawalWrite(
                userId, withdrawalNo, chain, amount, address, holdUntil, payoutDueAt,
                BigDecimal.ZERO, networkFee, networkFee, networkFee,
                penaltyPct, grossFee,
                nexBurned, nexOffsetRate, feeWaived, actualFee, netReceive,
                policy.policyVersion(), useNexFeeOffset,
                frozen ? "K3_RULE_FREEZE"
                        : delayed ? "K3_RULE_DELAY" : fastTrack ? "H1_PHASE_COOLDOWN" : null,
                "H1:M" + rhythm.currentMonth() + ":" + rhythm.currentPhase(),
                k4Priority, riskDecision.action(), k4Score, riskFacts.k4ModelVersion(), riskFacts.k4AsOf(),
                riskFacts.k4BandLowMax(), riskFacts.k4BandHighMin(), riskFacts.k4AutoEscalateScore(),
                status, failureReason,
                (frozen || delayed) ? "REVIEW_PENDING" : fastTrack ? "REVIEW_PASSED" : null);
        if (mapper.insertWithdrawal(write) != 1) throw new BizException(409, "WITHDRAWAL_CREATE_CONFLICT");
        if (riskDecision.held()) {
            withdrawalRiskRuleFacade.recordDecision(riskContext, riskDecision);
        }

        postWithdrawalLedgers(
                withdrawalNo, userId, amount, netReceive, actualNetworkFee, actualPenaltyFee, nexBurned);

        Attribution at = mapper.attribution(userId);
        if (at == null || at.accountAgeMonths() == null || !StringUtils.hasText(at.cohort())) {
            throw new BizException(409, "USER_EVENT_ATTRIBUTION_UNAVAILABLE");
        }
        Map<String, Object> detail = linked(
                "withdrawal_id", withdrawalNo, "amount_usdt", amount, "chain", chain,
                "network_confirm_usd", networkFee, "network_fee", networkFee,
                "policy_version", policy.policyVersion(), "use_nex_fee_offset", useNexFeeOffset,
                "penalty_fee_rate", penaltyPct, "penalty_fee", penaltyFee,
                "gross_fee", grossFee, "nex_burned", nexBurned,
                "fee_waived", feeWaived,
                "penalty_fee_waived", penaltyFeeWaived, "network_fee_waived", networkFeeWaived,
                "actual_penalty_fee", actualPenaltyFee, "actual_network_fee", actualNetworkFee,
                "actual_fee", actualFee, "net_receive", netReceive,
                "cooldown_days", rhythm.withdrawCooldownDays(), "hold_until", holdUntil,
                "small_amount_auto_review", fastTrack,
                "small_amount_threshold_usd", policy.smallAmountThresholdUsd(),
                "payout_sla_hours", policy.payoutSlaHours(), "payout_due_at", payoutDueAt,
                "risk_route", riskRoute, "k3_risk_route", riskDecision.action(),
                "strong_review", strongReview,
                "strong_review_threshold_usdt", strongReviewThreshold,
                "risk_rule_id", riskDecision.primaryRuleId(), "k4_priority", k4Priority,
                "k4_risk_score", riskFacts.k4RiskScore(), "k4_model_version", riskFacts.k4ModelVersion(),
                "k4_as_of", riskFacts.k4AsOf());
        audit.recordRequired(AuditLogWriteRequest.builder().action("D2_WITHDRAWAL_SUBMITTED")
                .resourceType("WITHDRAWAL").resourceId(withdrawalNo).bizNo(withdrawalNo)
                .userId(userId).actorId(userId).actorType("USER").actorUsername("user:" + userId)
                .riskLevel("HIGH").result("SUCCESS").detail(detail).build());
        outbox.publishUserEvent("WITHDRAWAL", withdrawalNo, "withdraw.submitted", userId,
                normalizePhase(at.phase()), at.accountAgeMonths(), at.cohort(), detail);
        if (k4Score >= riskFacts.k4AutoEscalateScore()) {
            outbox.publishUserEvent("WITHDRAWAL", withdrawalNo, "risk.withdraw_escalated", userId,
                    normalizePhase(at.phase()), at.accountAgeMonths(), at.cohort(), linked(
                            "withdrawal_id", withdrawalNo, "user_no", riskFacts.userNo(),
                            "risk_score", k4Score, "priority", k4Priority,
                            "notify_permission", "risk_k4_user_override", "model_version", riskFacts.k4ModelVersion(),
                            "score_as_of", riskFacts.k4AsOf()));
        }
        if (riskDecision.held()) {
            outbox.publishUserEvent("WITHDRAWAL", withdrawalNo, "risk.withdraw_held", userId,
                    normalizePhase(at.phase()), at.accountAgeMonths(), at.cohort(), linked(
                            "rule_id", riskDecision.primaryRuleId(),
                            "action", riskDecision.action(),
                            "withdrawalId", withdrawalNo,
                            "amountUsdt", amount,
                            "dimension", riskDecision.primaryDimension(),
                            "ts", System.currentTimeMillis()));
        }
        return ApiResult.ok(linked(
                "withdrawalNo", withdrawalNo, "amount", amount, "chain", chain, "status", status,
                "holdUntil", holdUntil, "networkConfirmUsd", networkFee,
                "smallAmountEligible", smallAmountEligible, "payoutDueAt", payoutDueAt,
                "networkFee", networkFee, "penaltyFeeRate", penaltyPct,
                "penaltyFee", penaltyFee, "grossFee", grossFee,
                "nexBurned", nexBurned, "feeWaived", feeWaived,
                "penaltyFeeWaived", penaltyFeeWaived, "networkFeeWaived", networkFeeWaived,
                "actualPenaltyFee", actualPenaltyFee, "actualNetworkFee", actualNetworkFee,
                "actualFee", actualFee,
                "netReceive", netReceive, "policyVersion", policy.policyVersion(),
                "useNexFeeOffset", useNexFeeOffset,
                "riskRoute", riskRoute, "k3RiskRoute", riskDecision.action(),
                "strongReview", strongReview, "strongReviewRequired", strongReview,
                "k4Priority", k4Priority,
                "riskRuleId", riskDecision.primaryRuleId(),
                "idSource", "server"));
    }

    private BigDecimal strongReviewThreshold() {
        String raw = config.activeValue(STRONG_REVIEW_THRESHOLD_KEY).orElse(null);
        try {
            BigDecimal value = new BigDecimal(raw == null ? "" : raw.trim());
            if (value.compareTo(MIN_WITHDRAWAL) < 0 || value.compareTo(new BigDecimal("10000000")) > 0) {
                throw new IllegalArgumentException("range");
            }
            return value.setScale(6, RoundingMode.UNNECESSARY);
        } catch (RuntimeException ex) {
            throw new BizException(503, "A3_STRONG_REVIEW_THRESHOLD_UNAVAILABLE");
        }
    }

    private boolean validK4Thresholds(WithdrawalRiskFacts facts) {
        return facts.k4BandLowMax() != null && facts.k4BandHighMin() != null
                && facts.k4AutoEscalateScore() != null
                && facts.k4BandLowMax() >= 0
                && facts.k4BandLowMax() < facts.k4BandHighMin()
                && facts.k4BandHighMin() <= 100
                && facts.k4AutoEscalateScore() >= facts.k4BandHighMin()
                && facts.k4AutoEscalateScore() <= 100;
    }

    private String k4Priority(int score, WithdrawalRiskFacts facts) {
        if (score >= facts.k4AutoEscalateScore()) return "ESCALATED";
        if (score >= facts.k4BandHighMin()) return "HIGH";
        if (score >= facts.k4BandLowMax()) return "NORMAL";
        return "LOW";
    }

    private String finalRiskRoute(
            int k4Score, WithdrawalRiskFacts facts,
            WithdrawalRiskDecision k3Decision) {
        if ("freeze".equals(k3Decision.action())) return "freeze";
        if (k4Score >= facts.k4AutoEscalateScore()) return "escalated-manual";
        if (k4Score >= facts.k4BandHighMin()) return "high-manual";
        if (k3Decision.held()) return k3Decision.action();
        if (k4Score >= facts.k4BandLowMax()) return "manual";
        return "fast-pass";
    }

    private String riskRouteEvidence(
            int k4Score, WithdrawalRiskFacts facts,
            WithdrawalRiskDecision k3Decision) {
        String k3 = k3Decision.held()
                ? "K3_ROUTE:" + k3Decision.action() + ":" + k3Decision.primaryRuleId() : null;
        String k4 = k4Score >= facts.k4AutoEscalateScore() ? "K4_ESCALATED:" + k4Score
                : k4Score >= facts.k4BandHighMin() ? "K4_HIGH_PRIORITY:" + k4Score
                : k4Score >= facts.k4BandLowMax() ? "K4_MANUAL:" + k4Score : null;
        return java.util.stream.Stream.of(k3, k4)
                .filter(StringUtils::hasText)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.joining(";"), value -> value.isEmpty() ? null : value));
    }

    private void postWithdrawalLedgers(
            String withdrawalNo,
            Long userId,
            BigDecimal amount,
            BigDecimal netReceive,
            BigDecimal actualNetworkFee,
            BigDecimal actualPenaltyFee,
            BigDecimal nexBurned) {
        BigDecimal totalUsdtOut = netReceive.add(actualNetworkFee).add(actualPenaltyFee);
        if (totalUsdtOut.compareTo(amount) != 0) {
            throw new BizException(503, "D5_LEDGER_COMPONENT_INVARIANT_BROKEN");
        }
        postPositiveLedger(
                withdrawalNo + ":USDT:PRINCIPAL", userId, "WITHDRAW_NET_PRINCIPAL", "USDT",
                netReceive, "D2 withdrawal net principal");
        postPositiveLedger(
                withdrawalNo + ":USDT:NETWORK_FEE", userId, "WITHDRAW_NETWORK_FEE", "USDT",
                actualNetworkFee, "D5 actual network fee after NEX offset");
        postPositiveLedger(
                withdrawalNo + ":USDT:PENALTY_FEE", userId, "WITHDRAW_PENALTY_FEE", "USDT",
                actualPenaltyFee, "H1 actual withdrawal penalty after NEX offset");
        postPositiveLedger(
                withdrawalNo + ":NEX:OFFSET", userId, "WITHDRAW_FEE_OFFSET", "NEX",
                nexBurned, "D5 NEX fee offset; penalty first, then network fee");
    }

    private void postPositiveLedger(
            String bizNo,
            Long userId,
            String bizType,
            String asset,
            BigDecimal amount,
            String remark) {
        if (amount.signum() <= 0) return;
        ledgerPostingFacade.postLedgerEntry(
                bizNo, userId, bizType, asset, "OUT", amount, "POSTED", remark);
    }

    private PolicySnapshot currentPolicy() {
        long version;
        try {
            version = requiredDecimal("withdrawal.d5.version").longValueExact();
        } catch (RuntimeException ex) {
            throw new BizException(503, "D5_POLICY_VERSION_UNAVAILABLE");
        }
        Map<String, BigDecimal> fees = new LinkedHashMap<>();
        fees.put("trc20", requiredDecimal("withdrawal.network_confirm_fee_usd.trc20"));
        fees.put("bep20", requiredDecimal("withdrawal.network_confirm_fee_usd.bep20"));
        fees.put("erc20", requiredDecimal("withdrawal.network_confirm_fee_usd.erc20"));
        if (fees.values().stream().anyMatch(fee -> fee.signum() < 0
                || fee.compareTo(new BigDecimal("25")) > 0
                || fee.multiply(new BigDecimal("2")).stripTrailingZeros().scale() > 0)) {
            throw new BizException(503, "D5_NETWORK_FEE_INVALID");
        }
        BigDecimal nexOffsetRate = requiredDecimal("withdrawal.nex_fee_offset_rate");
        if (nexOffsetRate.signum() <= 0) throw new BizException(503, "D5_NEX_OFFSET_INVALID");
        BigDecimal smallAmountThresholdUsd = requiredDecimal("withdrawal.small_amount_threshold_usd");
        int payoutSlaHours;
        try {
            payoutSlaHours = requiredDecimal("withdrawal.payout_sla_hours").intValueExact();
        } catch (ArithmeticException ex) {
            throw new BizException(503, "D5_PAYOUT_SLA_INVALID");
        }
        if (smallAmountThresholdUsd.signum() < 0
                || smallAmountThresholdUsd.compareTo(new BigDecimal("500")) > 0
                || payoutSlaHours < 1 || payoutSlaHours > 168) {
            throw new BizException(503, "D5_WD01_CONFIG_INVALID");
        }
        List<String> enabled = CHAINS.stream().sorted().filter(this::networkEnabled).toList();
        if (enabled.isEmpty()) throw new BizException(503, "D5_NETWORK_CONFIG_UNAVAILABLE");
        String versionMaterial = version + "|" + fees + "|" + nexOffsetRate.stripTrailingZeros().toPlainString()
                + "|" + smallAmountThresholdUsd.stripTrailingZeros().toPlainString()
                + "|" + payoutSlaHours + "|" + enabled;
        return new PolicySnapshot(version, Map.copyOf(fees), nexOffsetRate, smallAmountThresholdUsd,
                payoutSlaHours, enabled, hash(versionMaterial));
    }

    private boolean networkEnabled(String chain) {
        String key = switch (chain) {
            case "USDT-TRC20" -> "withdrawal.trc20.enabled";
            case "USDT-BEP20" -> "withdrawal.bep20.enabled";
            case "USDT-ERC20" -> "withdrawal.erc20.enabled";
            default -> throw new BizException(422, "WITHDRAWAL_CHAIN_INVALID");
        };
        return config.activeValue(key).map(String::trim).map(String::toLowerCase)
                .filter(Set.of("true", "false", "1", "0", "on", "off")::contains)
                .map(Set.of("true", "1", "on")::contains)
                .orElseThrow(() -> new BizException(503, "D5_NETWORK_CONFIG_UNAVAILABLE"));
    }

    private boolean withdrawGateEnabled() {
        return KillSwitchState.enabled(
                Optional.ofNullable(mapper.emergencyValue(WITHDRAW_KILLSWITCH_KEY)),
                Optional.ofNullable(mapper.emergencyValue(WITHDRAW_LEGACY_KILLSWITCH_KEY)));
    }

    private BigDecimal requiredDecimal(String key) {
        try {
            return config.activeValue(key).filter(StringUtils::hasText)
                    .map(String::trim).map(BigDecimal::new)
                    .orElseThrow(() -> new IllegalStateException(key));
        } catch (RuntimeException ex) {
            throw new BizException(503, "D5_CONFIG_UNAVAILABLE");
        }
    }

    private BigDecimal money(BigDecimal value) {
        if (value == null) throw new BizException(422, "WITHDRAWAL_AMOUNT_REQUIRED");
        try {
            return value.setScale(6, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new BizException(422, "WITHDRAWAL_AMOUNT_SCALE_INVALID");
        }
    }

    private BigDecimal ratio(BigDecimal value) {
        BigDecimal result = value == null ? null : value;
        if (result == null) throw new BizException(503, "H1_WITHDRAWAL_PENALTY_INVALID");
        return result.compareTo(BigDecimal.ONE) > 0 ? result.movePointLeft(2) : result;
    }

    private String networkKey(String chain) {
        return switch (chain) {
            case "USDT-TRC20" -> "trc20";
            case "USDT-BEP20" -> "bep20";
            case "USDT-ERC20" -> "erc20";
            default -> throw new BizException(422, "WITHDRAWAL_CHAIN_INVALID");
        };
    }

    private String normalizeChain(String value) {
        String result = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!CHAINS.contains(result)) throw new BizException(422, "WITHDRAWAL_CHAIN_INVALID");
        return result;
    }

    private boolean payoutAddressMatches(String chain, String submitted, String configured) {
        if (!StringUtils.hasText(submitted) || !StringUtils.hasText(configured)) return false;
        String expected = configured.trim();
        // TRON Base58 is case-sensitive. Only EVM hexadecimal addresses may be
        // compared case-insensitively after their format was validated when saved.
        return "USDT-ERC20".equals(chain)
                && submitted.matches("^0x[0-9a-fA-F]{40}$")
                && expected.matches("^0x[0-9a-fA-F]{40}$")
                ? submitted.equalsIgnoreCase(expected)
                : submitted.equals(expected);
    }

    private String normalizeAddress(String value) {
        String result = value == null ? "" : value.trim();
        if (result.length() < 11 || result.length() > 128 || !result.matches("[A-Za-z0-9:_-]+")) {
            throw new BizException(422, "WITHDRAWAL_ADDRESS_INVALID");
        }
        return result;
    }

    private String requireKey(String value) {
        if (!StringUtils.hasText(value) || value.trim().length() > 128) {
            throw new BizException(400, "IDEMPOTENCY_KEY_REQUIRED");
        }
        return value.trim();
    }

    private String normalizePhase(String phase) {
        String result = phase == null ? "P1" : phase.trim().toUpperCase(Locale.ROOT);
        return result.matches("P[1-6]") ? result : "P1";
    }

    private BigDecimal safe(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Map<String, Object> linked(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        return map;
    }

    private record PolicySnapshot(
            long version,
            Map<String, BigDecimal> networkConfirmFeeUsd,
            BigDecimal nexFeeOffsetRate,
            BigDecimal smallAmountThresholdUsd,
            int payoutSlaHours,
            List<String> enabledNetworks,
            String policyVersion) { }
}
