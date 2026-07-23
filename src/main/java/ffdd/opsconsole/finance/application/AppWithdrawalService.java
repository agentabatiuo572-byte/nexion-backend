package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.mapper.AppWithdrawalMapper;
import ffdd.opsconsole.finance.mapper.AppWithdrawalMapper.Attribution;
import ffdd.opsconsole.finance.mapper.AppWithdrawalMapper.KycWalletRow;
import ffdd.opsconsole.finance.mapper.AppWithdrawalMapper.LedgerWrite;
import ffdd.opsconsole.finance.mapper.AppWithdrawalMapper.WalletRow;
import ffdd.opsconsole.finance.mapper.AppWithdrawalMapper.WithdrawalRiskFacts;
import ffdd.opsconsole.finance.mapper.AppWithdrawalMapper.WithdrawalWrite;
import ffdd.opsconsole.growth.facade.GrowthRhythmFacade;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.risk.facade.KycReviewTriggerResult;
import ffdd.opsconsole.risk.facade.RiskKycReviewFacade;
import ffdd.opsconsole.risk.facade.WithdrawalRiskContext;
import ffdd.opsconsole.risk.facade.WithdrawalRiskDecision;
import ffdd.opsconsole.risk.facade.WithdrawalRiskRuleFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
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
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** D2/D5 user entry point; H1 withdrawal dials are snapshotted at submission. */
@Service
@RequiredArgsConstructor
public class AppWithdrawalService {
    private static final Set<String> CHAINS = Set.of("USDT-TRC20", "USDT-ERC20");
    private static final BigDecimal MIN_WITHDRAWAL = new BigDecimal("20.000000");

    private final AppWithdrawalMapper mapper;
    private final PlatformConfigFacade config;
    private final GrowthRhythmFacade growthRhythmFacade;
    private final AdminIdempotencyService idempotency;
    private final AuditLogService audit;
    private final EventOutboxService outbox;
    private final WithdrawalRiskRuleFacade withdrawalRiskRuleFacade;
    private final RiskKycReviewFacade riskKycReviewFacade;

    public ApiResult<Map<String, Object>> list(Long userId) {
        if (userId == null || mapper.findActiveUser(userId) == null) throw new BizException(404, "USER_NOT_FOUND");
        return ApiResult.ok(linked("withdrawals", mapper.userWithdrawals(userId, 50),
                "source", "nx_withdrawal_order"));
    }

    @Transactional(rollbackFor = Exception.class)
    @SuppressWarnings({"rawtypes", "unchecked"})
    public ApiResult<Map<String, Object>> submit(
            Long userId, BigDecimal amount, String chain, String address, String idempotencyKey) {
        if (userId == null || mapper.lockActiveUser(userId) == null) throw new BizException(404, "USER_NOT_FOUND");
        BigDecimal normalizedAmount = money(amount);
        String normalizedChain = normalizeChain(chain);
        String normalizedAddress = normalizeAddress(address);
        String requestHash = hash(userId + "|" + normalizedAmount + "|" + normalizedChain + "|" + normalizedAddress);
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotency.execute(
                "USER_WITHDRAWAL_SUBMIT", requireKey(idempotencyKey), requestHash, ApiResult.class,
                () -> submitOnce(userId, normalizedAmount, normalizedChain, normalizedAddress));
    }

    private ApiResult<Map<String, Object>> submitOnce(
            Long userId, BigDecimal amount, String chain, String address) {
        KycWalletRow kyc = mapper.lockKycWallet(userId);
        if (kyc == null || !"APPROVED".equalsIgnoreCase(kyc.status())
                || !StringUtils.hasText(kyc.pairedAddress()) || !StringUtils.hasText(kyc.network())) {
            return ApiResult.fail(409, "WITHDRAWAL_KYC_WALLET_REQUIRED");
        }
        if (!pairedAddressMatches(chain, address, kyc.pairedAddress())
                || !chain.equals(normalizeStoredChain(kyc.network()))) {
            return ApiResult.fail(409, "WITHDRAWAL_PAIRED_WALLET_MISMATCH");
        }
        if (!networkEnabled(chain)) return ApiResult.fail(409, "WITHDRAWAL_NETWORK_DISABLED");

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

        GrowthRhythmSnapshot rhythm = growthRhythmFacade.snapshot();
        if (rhythm == null || rhythm.currentMonth() <= 0 || rhythm.withdrawCooldownDays() <= 0
                || rhythm.withdrawPenaltyFeeRate() == null) {
            throw new BizException(503, "H1_WITHDRAWAL_DIAL_UNAVAILABLE");
        }
        BigDecimal penaltyPct = rhythm.withdrawPenaltyFeeRate();
        BigDecimal penaltyRate = ratio(penaltyPct);
        if (penaltyRate.signum() < 0 || penaltyRate.compareTo(BigDecimal.ONE) > 0) {
            throw new BizException(503, "H1_WITHDRAWAL_PENALTY_INVALID");
        }
        BigDecimal nexOffsetRate = requiredDecimal("withdrawal.nex_fee_offset_rate");
        if (nexOffsetRate.signum() <= 0) throw new BizException(503, "D5_NEX_OFFSET_INVALID");

        BigDecimal grossFee = amount.multiply(penaltyRate).setScale(6, RoundingMode.DOWN);
        BigDecimal requiredNex = grossFee.divide(nexOffsetRate, 6, RoundingMode.UP);
        BigDecimal nexBurned = safe(wallet.nexAvailable()).min(requiredNex).setScale(6, RoundingMode.DOWN);
        BigDecimal feeWaived = nexBurned.multiply(nexOffsetRate).min(grossFee).setScale(6, RoundingMode.DOWN);
        BigDecimal actualFee = grossFee.subtract(feeWaived).max(BigDecimal.ZERO).setScale(6, RoundingMode.DOWN);
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
        KycReviewTriggerResult k5Review = riskKycReviewFacade.triggerLargeWithdrawalReview(
                riskFacts.userNo(), amount, kyc.status(), withdrawalNo, "system",
                "D2 queue entry server-canonical KYC review");
        if (k5Review == null || (k5Review.requiresReview() && !StringUtils.hasText(k5Review.ticketId()))) {
            throw new BizException(503, "K5_WITHDRAWAL_REVIEW_UNAVAILABLE");
        }
        int k4Score = riskFacts.k4RiskScore();
        String k4Priority = k4Priority(k4Score, riskFacts);
        boolean frozen = "freeze".equals(riskDecision.action()) || k5Review.requiresReview();
        boolean delayed = "delay".equals(riskDecision.action()) && !k5Review.requiresReview();
        boolean fastTrack = k4Score < riskFacts.k4BandLowMax()
                && "pass".equals(riskDecision.action()) && !k5Review.requiresReview();
        // H1 cooldown remains authoritative: low-risk fast-track is auto-reviewed only after the hold expires.
        String status = frozen ? "FROZEN" : (fastTrack || delayed) ? "EXTENDED_HOLD" : "REVIEW_PENDING";
        String riskRoute = finalRiskRoute(k4Score, riskFacts, riskDecision, k5Review);
        String failureReason = fastTrack ? "H1_COOLDOWN_FAST_TRACK"
                : riskRouteEvidence(k4Score, riskFacts, riskDecision, k5Review);
        if (mapper.reserveFunds(userId, amount, nexBurned, wallet.version()) != 1) {
            throw new BizException(409, "WITHDRAWAL_WALLET_CONFLICT");
        }
        WithdrawalWrite write = new WithdrawalWrite(
                userId, withdrawalNo, chain, amount, address, holdUntil, penaltyPct, grossFee,
                nexBurned, nexOffsetRate, feeWaived, actualFee, netReceive,
                frozen ? (k5Review.requiresReview() ? "K5_REVIEW" : "K3_RULE_FREEZE")
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

        BigDecimal usdtAfter = safe(wallet.usdtAvailable()).subtract(amount);
        requireLedger(new LedgerWrite(userId, withdrawalNo + ":USDT", "WITHDRAW_SUBMIT", "USDT",
                amount, usdtAfter, "D2 withdrawal funds reserved"));
        if (nexBurned.signum() > 0) {
            requireLedger(new LedgerWrite(userId, withdrawalNo + ":NEX", "WITHDRAW_FEE_OFFSET", "NEX",
                    nexBurned, safe(wallet.nexAvailable()).subtract(nexBurned), "D5 optional NEX fee offset"));
        }

        Attribution at = mapper.attribution(userId);
        if (at == null || at.accountAgeMonths() == null || !StringUtils.hasText(at.cohort())) {
            throw new BizException(409, "USER_EVENT_ATTRIBUTION_UNAVAILABLE");
        }
        Map<String, Object> detail = linked(
                "withdrawal_id", withdrawalNo, "amount_usdt", amount, "chain", chain,
                "penalty_fee_rate", penaltyPct, "gross_fee", grossFee, "nex_burned", nexBurned,
                "fee_waived", feeWaived, "actual_fee", actualFee, "net_receive", netReceive,
                "cooldown_days", rhythm.withdrawCooldownDays(), "hold_until", holdUntil,
                "risk_route", riskRoute, "k3_risk_route", riskDecision.action(),
                "risk_rule_id", riskDecision.primaryRuleId(), "k4_priority", k4Priority,
                "k4_risk_score", riskFacts.k4RiskScore(), "k4_model_version", riskFacts.k4ModelVersion(),
                "k4_as_of", riskFacts.k4AsOf(),
                "k5_ticket_id", k5Review.ticketId());
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
                "holdUntil", holdUntil, "penaltyFeeRate", penaltyPct, "grossFee", grossFee,
                "nexBurned", nexBurned, "feeWaived", feeWaived, "actualFee", actualFee,
                "netReceive", netReceive, "riskRoute", riskRoute, "k3RiskRoute", riskDecision.action(),
                "k4Priority", k4Priority,
                "riskRuleId", riskDecision.primaryRuleId(), "k5TicketId", k5Review.ticketId(),
                "idSource", "server"));
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
            WithdrawalRiskDecision k3Decision, KycReviewTriggerResult k5Review) {
        if (k5Review.requiresReview() || "freeze".equals(k3Decision.action())) return "freeze";
        if (k4Score >= facts.k4AutoEscalateScore()) return "escalated-manual";
        if (k4Score >= facts.k4BandHighMin()) return "high-manual";
        if (k3Decision.held()) return k3Decision.action();
        if (k4Score >= facts.k4BandLowMax()) return "manual";
        return "fast-pass";
    }

    private String riskRouteEvidence(
            int k4Score, WithdrawalRiskFacts facts,
            WithdrawalRiskDecision k3Decision, KycReviewTriggerResult k5Review) {
        String k5 = k5Review.requiresReview() ? "K5_REVIEW:" + k5Review.ticketId() : null;
        String k3 = k3Decision.held()
                ? "K3_ROUTE:" + k3Decision.action() + ":" + k3Decision.primaryRuleId() : null;
        String k4 = k4Score >= facts.k4AutoEscalateScore() ? "K4_ESCALATED:" + k4Score
                : k4Score >= facts.k4BandHighMin() ? "K4_HIGH_PRIORITY:" + k4Score
                : k4Score >= facts.k4BandLowMax() ? "K4_MANUAL:" + k4Score : null;
        return java.util.stream.Stream.of(k5, k3, k4)
                .filter(StringUtils::hasText)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.joining(";"), value -> value.isEmpty() ? null : value));
    }

    private void requireLedger(LedgerWrite row) {
        if (mapper.insertLedger(row) != 1) throw new BizException(409, "WITHDRAWAL_LEDGER_CONFLICT");
    }

    private boolean networkEnabled(String chain) {
        String key = "USDT-TRC20".equals(chain) ? "withdrawal.trc20.enabled" : "withdrawal.erc20.enabled";
        return config.activeValue(key).map(String::trim).map(String::toLowerCase)
                .filter(Set.of("true", "false", "1", "0", "on", "off")::contains)
                .map(Set.of("true", "1", "on")::contains)
                .orElseThrow(() -> new BizException(503, "D5_NETWORK_CONFIG_UNAVAILABLE"));
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

    private String normalizeChain(String value) {
        String result = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!CHAINS.contains(result)) throw new BizException(422, "WITHDRAWAL_CHAIN_INVALID");
        return result;
    }

    private String normalizeStoredChain(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "TRC20", "USDT-TRC20" -> "USDT-TRC20";
            case "ERC20", "USDT-ERC20" -> "USDT-ERC20";
            default -> normalized;
        };
    }

    private boolean pairedAddressMatches(String chain, String submitted, String paired) {
        if (!StringUtils.hasText(submitted) || !StringUtils.hasText(paired)) return false;
        String expected = paired.trim();
        // TRON Base58 is case-sensitive. Only EVM hexadecimal addresses may be
        // compared case-insensitively after their format was validated at pairing.
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
}
