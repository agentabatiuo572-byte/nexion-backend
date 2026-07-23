package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.growth.dto.GrowthConfigUpdateRequest;
import ffdd.opsconsole.growth.dto.ReferralSettlementRunRequest;
import ffdd.opsconsole.growth.mapper.ReferralRewardMapper;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class OpsReferralRewardService {
    private static final String EFFECTIVE_AT_KEY = "K.rewards.referral.effectiveAt";
    private static final Map<String, String> STORAGE_KEYS = Map.of(
            "newcomer.usdt", "K.rewards.welcomeGift.usdtAmount",
            "newcomer.nex", "K.rewards.welcomeGift.nexAmount",
            "newcomer.lockMode", "K.rewards.welcomeGift.lockMode",
            "inviter.nex", "K.rewards.inviterReward.nexAmount");
    // Money defaults must be fail-safe. Product prototypes used 5/20/200 as display
    // samples; carrying those values into the real ledger would create unapproved awards.
    private static final Map<String, String> SAFE_DEFAULTS = Map.of(
            "newcomer.usdt", "0", "newcomer.nex", "0",
            "newcomer.lockMode", "risk_bucket", "inviter.nex", "0");
    private static final Set<String> PARAMS = STORAGE_KEYS.keySet();
    private final ReferralRewardMapper mapper;
    private final PlatformConfigFacade config;
    private final TreasuryLedgerPostingFacade ledger;
    private final AuditLogService audit;
    private final AdminIdempotencyService idempotency;
    private final TreasuryCoverageFacade coverage;
    private final EventOutboxService outbox;
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;

    public Map<String, Object> overview() {
        Map<String, Object> params = new LinkedHashMap<>();
        PARAMS.stream().sorted().forEach(key -> params.put(key,
                "newcomer.lockMode".equals(key) ? lockMode() : amount(key)));
        EffectiveRewards effectiveRewards = effectiveRewards();
        LocalDateTime effectiveAt = effectiveAtOrNow();
        boolean holdRisky = "risk_bucket".equals(lockMode());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("params", params);
        result.put("rhythmMonth", effectiveRewards.rhythmMonth());
        result.put("newcomerMultiplier", effectiveRewards.newcomerMultiplier());
        result.put("inviterMultiplier", effectiveRewards.inviterMultiplier());
        result.put("effectiveRewards", Map.of(
                "newcomer.usdt", effectiveRewards.newcomerUsdt(),
                "newcomer.nex", effectiveRewards.newcomerNex(),
                "inviter.nex", effectiveRewards.inviterNex()));
        result.put("pending", mapper.totalPending(effectiveAt, holdRisky));
        result.put("settled", mapper.totalSettled());
        result.put("blockedByK2", mapper.totalBlockedByK2(effectiveAt, holdRisky));
        result.put("recentSettlements", mapper.recentSettlements(20));
        result.put("source", "nx_user.sponsor_user_id");
        result.put("settlementMode", "REAL_WALLET_LEDGER");
        result.put("effectiveAt", effectiveAt);
        return result;
    }

    @Transactional
    public Map<String, Object> updateParam(String paramKey, String idempotencyKey, GrowthConfigUpdateRequest request) {
        validateIdempotency(idempotencyKey);
        if (!PARAMS.contains(paramKey)) {
            throw new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "REFERRAL_PARAM_NOT_ALLOWED");
        }
        String reason = requireReason(request == null ? null : request.reason());
        String value = normalizeParam(paramKey, request.value());
        requireRewardMutex();
        return idempotency.execute("REFERRAL_REWARD_PARAM", idempotencyKey,
                hash(paramKey + ":" + value + ":" + reason), Map.class, () -> {
                    String before = rawValue(paramKey);
                    if (amplifies(paramKey, before, value)) requireHealthyCoverage();
                    if (config.activeValue(EFFECTIVE_AT_KEY).isEmpty()) {
                        config.upsertAdminValue(EFFECTIVE_AT_KEY, Instant.now().toString(), "DATETIME", "GROWTH_REFERRAL",
                                "H8 first effective time; historical referrals are never retroactively paid");
                    }
                    config.upsertAdminValue(STORAGE_KEYS.get(paramKey), value,
                            "newcomer.lockMode".equals(paramKey) ? "STRING" : "DECIMAL", "GROWTH_REFERRAL",
                            "H8 邀请奖励真实发奖参数；" + reason);
                    String operator = actor(request.operator());
                    audit("REFERRAL_REWARD_PARAM_UPDATE", paramKey, operator, idempotencyKey,
                            Map.of("before", before, "after", value, "reason", reason,
                                    "coverage", coverageDetail()));
                    outbox.publish("REFERRAL_REWARD_PARAM", paramKey, "H8_REFERRAL_REWARD_PARAM_CHANGED",
                            Map.of("paramKey", paramKey, "before", before, "after", value,
                                    "operator", operator, "reason", reason, "idempotencyKey", idempotencyKey));
                    return Map.of("key", paramKey, "value", value, "status", "UPDATED");
                });
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Map<String, Object> runSettlements(String idempotencyKey, ReferralSettlementRunRequest request) {
        validateIdempotency(idempotencyKey);
        String reason = requireReason(request == null ? null : request.reason());
        int limit = Math.min(100, Math.max(1, request.limit() == null ? 20 : request.limit()));
        // The database mutex survives until commit, serializes all H8 batches across
        // instances, and also freezes the reward configuration for the whole batch.
        requireRewardMutex();
        return idempotency.execute("REFERRAL_REWARD_SETTLEMENT", idempotencyKey,
                hash(limit + ":" + reason), Map.class, () -> settle(limit, reason, actor(request.operator()), idempotencyKey));
    }

    private Map<String, Object> settle(int limit, String reason, String operator, String key) {
        requireHealthyCoverage();
        LocalDateTime effectiveAt = effectiveAtRequired();
        EffectiveRewards effectiveRewards = effectiveRewards();
        BigDecimal newcomerMultiplier = effectiveRewards.newcomerMultiplier();
        BigDecimal inviterMultiplier = effectiveRewards.inviterMultiplier();
        BigDecimal newcomerUsdt = effectiveRewards.newcomerUsdt();
        BigDecimal newcomerNex = effectiveRewards.newcomerNex();
        BigDecimal inviterNex = effectiveRewards.inviterNex();
        String lockMode = lockMode();
        String configSnapshot = "usdt=" + newcomerUsdt.toPlainString() + ",newcomerNex="
                + newcomerNex.toPlainString() + ",inviterNex=" + inviterNex.toPlainString()
                + ",newcomerMultiplier=" + newcomerMultiplier.toPlainString()
                + ",inviterMultiplier=" + inviterMultiplier.toPlainString() + ",lockMode=" + lockMode;
        if (newcomerUsdt.signum() == 0 && newcomerNex.signum() == 0 && inviterNex.signum() == 0) {
            throw new BizException(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "REFERRAL_REWARD_NOT_CONFIGURED");
        }
        int settled = 0;
        int skipped = 0;
        boolean holdRisky = "risk_bucket".equals(lockMode);
        for (ReferralRewardMapper.ReferralRow row : mapper.findPendingReferrals(effectiveAt, holdRisky, limit)) {
            String settlementNo = "REF-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24).toUpperCase();
            if (mapper.insertSettlement(settlementNo, row.invitedUserId(), row.inviterUserId(),
                    newcomerUsdt, newcomerNex, inviterNex, lockMode, configSnapshot, operator, reason, key,
                    effectiveAt, holdRisky) != 1) {
                skipped++;
                continue;
            }
            mapper.creditWallet(row.invitedUserId(), newcomerUsdt, newcomerNex);
            mapper.creditWallet(row.inviterUserId(), BigDecimal.ZERO, inviterNex);
            post(settlementNo + ":NEWCOMER", row.invitedUserId(), newcomerUsdt, newcomerNex, "新用户邀请奖励");
            post(settlementNo + ":INVITER", row.inviterUserId(), BigDecimal.ZERO, inviterNex, "邀请人奖励");
            audit("REFERRAL_REWARD_SETTLED", settlementNo, operator, key,
                    Map.of("invitedUserId", row.invitedUserId(), "inviterUserId", row.inviterUserId(),
                            "newcomerUsdt", newcomerUsdt, "newcomerNex", newcomerNex,
                            "inviterNex", inviterNex, "lockMode", lockMode, "reason", reason));
            outbox.publish("REFERRAL_REWARD_SETTLEMENT", settlementNo, "H8_REFERRAL_REWARD_SETTLED",
                    Map.of("invitedUserId", row.invitedUserId(), "inviterUserId", row.inviterUserId(),
                            "newcomerUsdt", newcomerUsdt, "newcomerNex", newcomerNex,
                            "inviterNex", inviterNex, "lockMode", lockMode));
            settled++;
        }
        // Recompute from the wallet state written by this transaction. If the batch
        // itself crosses B1 or makes NEX valuation unavailable, throwing here rolls
        // back every settlement, wallet credit, ledger entry and audit in the batch.
        requireHealthyCoverage();
        audit("REFERRAL_REWARD_SETTLEMENT_RUN", "batch", operator, key,
                Map.of("limit", limit, "settled", settled, "skipped", skipped, "reason", reason,
                        "lockMode", lockMode, "effectiveAt", effectiveAt, "coverage", coverageDetail()));
        outbox.publish("REFERRAL_REWARD_SETTLEMENT", key, "H8_REFERRAL_REWARD_BATCH_COMPLETED",
                Map.of("limit", limit, "settled", settled, "skipped", skipped,
                        "operator", operator, "idempotencyKey", key));
        return Map.of("settled", settled, "skipped", skipped, "limit", limit);
    }

    private EffectiveRewards effectiveRewards() {
        GrowthRhythmSnapshot rhythm = GrowthRhythmSnapshot.from(config, readTimeSeedPolicy);
        BigDecimal newcomerMultiplier = rhythm.newUserBonusMultiplier();
        BigDecimal inviterMultiplier = rhythm.inviteRewardMultiplier();
        if (newcomerMultiplier.signum() <= 0 || inviterMultiplier.signum() <= 0) {
            throw new BizException(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "H1_REWARD_MULTIPLIER_UNAVAILABLE");
        }
        return new EffectiveRewards(
                rhythm.currentMonth(), newcomerMultiplier, inviterMultiplier,
                amount("newcomer.usdt").multiply(newcomerMultiplier),
                amount("newcomer.nex").multiply(newcomerMultiplier),
                amount("inviter.nex").multiply(inviterMultiplier));
    }

    private record EffectiveRewards(
            int rhythmMonth,
            BigDecimal newcomerMultiplier,
            BigDecimal inviterMultiplier,
            BigDecimal newcomerUsdt,
            BigDecimal newcomerNex,
            BigDecimal inviterNex) {
    }

    private void requireRewardMutex() {
        if (!"H8_REWARD".equals(mapper.lockRewardMutation())) {
            throw new BizException(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "H8_REWARD_MUTEX_UNAVAILABLE");
        }
    }

    private void post(String bizNo, Long userId, BigDecimal usdt, BigDecimal nex, String remark) {
        if (usdt.signum() > 0) {
            ledger.postLedgerEntry(bizNo, userId, "REFERRAL_REWARD", "USDT", "IN", usdt, "SUCCESS", remark);
        }
        if (nex.signum() > 0) {
            ledger.postLedgerEntry(bizNo, userId, "REFERRAL_REWARD", "NEX", "IN", nex, "SUCCESS", remark);
        }
    }

    private BigDecimal amount(String key) {
        return parseAmount(key, rawValue(key));
    }

    private String rawValue(String key) {
        return config.activeValue(STORAGE_KEYS.get(key)).orElse(SAFE_DEFAULTS.get(key));
    }

    private String lockMode() {
        String mode = rawValue("newcomer.lockMode").trim().toLowerCase();
        if (!Set.of("risk_bucket", "direct").contains(mode)) {
            throw new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "REFERRAL_REWARD_LOCK_MODE_INVALID");
        }
        return mode;
    }

    private String normalizeParam(String key, String value) {
        if ("newcomer.lockMode".equals(key)) {
            String normalized = value == null ? "" : value.trim().toLowerCase();
            if (!Set.of("risk_bucket", "direct").contains(normalized)) {
                throw new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "REFERRAL_REWARD_LOCK_MODE_INVALID");
            }
            return normalized;
        }
        return parseAmount(key, value).toPlainString();
    }

    private BigDecimal parseAmount(String key, String value) {
        try {
            BigDecimal parsed = new BigDecimal(value).setScale(6, RoundingMode.UNNECESSARY);
            BigDecimal max = "newcomer.usdt".equals(key) ? new BigDecimal("50")
                    : "newcomer.nex".equals(key) ? new BigDecimal("500") : new BigDecimal("999999999999.999999");
            if (parsed.signum() < 0 || parsed.compareTo(max) > 0) {
                throw new NumberFormatException();
            }
            return parsed.stripTrailingZeros();
        } catch (RuntimeException ex) {
            throw new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "REFERRAL_REWARD_AMOUNT_INVALID");
        }
    }

    private boolean amplifies(String key, String before, String after) {
        if ("newcomer.lockMode".equals(key)) return !"direct".equalsIgnoreCase(before) && "direct".equalsIgnoreCase(after);
        return parseAmount(key, after).compareTo(parseAmount(key, before)) > 0;
    }

    private TreasuryCoverageSnapshot requireHealthyCoverage() {
        TreasuryCoverageSnapshot snapshot = coverage.snapshot();
        if (snapshot == null || !snapshot.reliable() || snapshot.coverageRatio() == null
                || snapshot.redlinePct() == null || snapshot.coverageRatio().signum() <= 0
                || snapshot.redlinePct().signum() <= 0) {
            throw new BizException(422, "B1_COVERAGE_DATA_UNAVAILABLE");
        }
        if (snapshot.coverageRatio().compareTo(snapshot.redlinePct()) < 0) {
            throw new BizException(422, "B1_COVERAGE_BELOW_REDLINE");
        }
        return snapshot;
    }

    private Map<String, Object> coverageDetail() {
        TreasuryCoverageSnapshot snapshot = coverage.snapshot();
        return Map.of("coverageRatio", snapshot.coverageRatio(), "redlinePct", snapshot.redlinePct(),
                "reliable", snapshot.reliable());
    }

    private LocalDateTime effectiveAtRequired() {
        String raw = config.activeValue(EFFECTIVE_AT_KEY).orElseGet(() -> {
            String initialized = Instant.now().toString();
            config.upsertAdminValue(EFFECTIVE_AT_KEY, initialized, "DATETIME", "GROWTH_REFERRAL",
                    "H8 activation time; historical referrals are never retroactively paid");
            return initialized;
        });
        try {
            return LocalDateTime.ofInstant(Instant.parse(raw), ZoneOffset.UTC);
        } catch (RuntimeException ex) {
            throw new BizException(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "REFERRAL_REWARD_EFFECTIVE_AT_INVALID");
        }
    }

    private LocalDateTime effectiveAtOrNow() {
        return config.activeValue(EFFECTIVE_AT_KEY).map(raw -> {
            try { return LocalDateTime.ofInstant(Instant.parse(raw), ZoneOffset.UTC); }
            catch (RuntimeException ignored) { return LocalDateTime.now(ZoneOffset.UTC); }
        }).orElseGet(() -> LocalDateTime.now(ZoneOffset.UTC));
    }

    private void audit(String action, String resourceId, String operator, String key, Map<String, Object> detail) {
        Map<String, Object> safe = new LinkedHashMap<>(detail);
        safe.put("idempotencyKey", key);
        audit.recordRequired(AuditLogWriteRequest.builder().action(action).resourceType("REFERRAL_REWARD")
                .resourceId(resourceId).actorUsername(StringUtils.hasText(operator) ? operator.trim() : "unknown-admin")
                .riskLevel("HIGH").detail(safe).build());
    }

    private String actor(String fallback) {
        String resolved = AdminActorResolver.resolve(fallback);
        return StringUtils.hasText(resolved) ? resolved.trim() : "system";
    }

    private String requireReason(String reason) {
        if (!StringUtils.hasText(reason) || reason.trim().length() < 8 || reason.trim().length() > 200) {
            throw new BizException(OpsErrorCode.REASON_REQUIRED.httpStatus(), "OPERATION_REASON_TOO_SHORT");
        }
        return reason.trim();
    }

    private void validateIdempotency(String key) {
        if (!StringUtils.hasText(key)) {
            throw new BizException(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
