package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.growth.dto.ReferralRewardParamUpdateRequest;
import ffdd.opsconsole.growth.dto.ReferralSettlementRunRequest;
import ffdd.opsconsole.growth.domain.ReferralRewardPublicConfigView;
import ffdd.opsconsole.growth.mapper.ReferralRewardMapper;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.platform.application.A2ReplayContext;
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
import java.util.List;
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
    private static final String VERSION_KEY = "K.rewards.referral.version";
    // This product ceiling is exact in JavaScript and remains below DECIMAL(18,6).
    private static final BigDecimal MAX_EFFECTIVE_REWARD = new BigDecimal("999999999.000000");
    // H1 payout multipliers are bounded to 4x. Capping the editable base here
    // prevents a later valid H1 change from making H8 unreadable or unpayable.
    private static final BigDecimal MAX_INVITER_BASE = new BigDecimal("249999999.750000");
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
        LocalDateTime effectiveAt = effectiveAtRequired();
        String currentLockMode = lockMode();
        long currentVersion = version(config.activeValue(VERSION_KEY).orElse("1"));
        String snapshotHash = rewardSnapshotHash(
                currentVersion, effectiveAt, currentLockMode, effectiveRewards);
        boolean holdRisky = "risk_bucket".equals(currentLockMode);
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
        result.put("effectiveAt", effectiveAt.toInstant(ZoneOffset.UTC));
        result.put("version", currentVersion);
        result.put("rewardSnapshotHash", snapshotHash);
        return result;
    }

    /**
     * App/H5 登录前展示使用的服务端权威奖励投影。
     *
     * <p>这里返回 H1 当月倍率生效后的真实发放金额，而不是后台配置的基础金额；
     * 任一配置或 H1 节奏不可用时沿用现有 fail-closed 异常，不回退到原型常量。
     */
    public ReferralRewardPublicConfigView publicConfig() {
        EffectiveRewards rewards = effectiveRewards();
        return new ReferralRewardPublicConfigView(
                new ReferralRewardPublicConfigView.WelcomeGift(
                        lockMode(), rewards.newcomerUsdt(), rewards.newcomerNex()),
                new ReferralRewardPublicConfigView.InviterReward(rewards.inviterNex()),
                rewards.rhythmMonth(),
                rewards.newcomerMultiplier(),
                rewards.inviterMultiplier(),
                effectiveAtRequired().toInstant(ZoneOffset.UTC),
                List.of(
                        "nx_config_item:K.rewards.*",
                        "nx_config_item:growth.phase.month.*",
                        "nx_user.sponsor_user_id"));
    }

    @Transactional
    public Map<String, Object> updateParam(
            String paramKey,
            String idempotencyKey,
            ReferralRewardParamUpdateRequest request) {
        try {
            validateIdempotency(idempotencyKey);
            if (!PARAMS.contains(paramKey)) {
                throw new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "REFERRAL_PARAM_NOT_ALLOWED");
            }
            String reason = requireReason(request == null ? null : request.reason());
            String value = normalizeParam(paramKey, request.value());
            long expectedVersion = requireExpectedVersion(request.expectedVersion());
            requireRewardMutex();
            return idempotency.execute("REFERRAL_REWARD_PARAM", idempotencyKey,
                    hash(paramKey + ":" + value + ":" + expectedVersion + ":" + reason), Map.class, () -> {
                        long currentVersion = version(config.activeValueForUpdate(VERSION_KEY).orElse("1"));
                        if (expectedVersion != currentVersion) {
                            throw new BizException(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(),
                                    "H8_CONFIG_VERSION_CONFLICT");
                        }
                        String before = rawValue(paramKey);
                        if (amplifies(paramKey, before, value)) requireHealthyCoverage();
                        if (config.activeValue(EFFECTIVE_AT_KEY).isEmpty()) {
                            config.upsertAdminValue(EFFECTIVE_AT_KEY, Instant.now().toString(), "DATETIME", "GROWTH_REFERRAL",
                                    "H8 first effective time; historical referrals are never retroactively paid");
                        }
                        config.upsertAdminValue(STORAGE_KEYS.get(paramKey), value,
                                "newcomer.lockMode".equals(paramKey) ? "STRING" : "DECIMAL", "GROWTH_REFERRAL",
                                "H8 邀请奖励真实发奖参数；" + reason);
                        long nextVersion = Math.addExact(currentVersion, 1L);
                        config.upsertAdminValue(VERSION_KEY, String.valueOf(nextVersion), "NUMBER",
                                "GROWTH_REFERRAL", "H8 referral reward configuration version");
                        String operator = actor(request.operator());
                        audit("REFERRAL_REWARD_PARAM_UPDATE", paramKey, operator, idempotencyKey,
                                Map.of("before", before, "after", value, "reason", reason,
                                        "versionBefore", currentVersion, "versionAfter", nextVersion,
                                        "coverage", coverageDetail()));
                        outbox.publish("REFERRAL_REWARD_PARAM", paramKey, "H8_REFERRAL_REWARD_PARAM_CHANGED",
                                Map.of("paramKey", paramKey, "before", before, "after", value,
                                        "version", nextVersion, "operator", operator, "reason", reason,
                                        "idempotencyKey", idempotencyKey));
                        return Map.of("key", paramKey, "value", value, "status", "UPDATED",
                                "version", nextVersion);
                    });
        } catch (RuntimeException ex) {
            rejectedAudit("REFERRAL_REWARD_PARAM_UPDATE_REJECTED", paramKey,
                    request == null ? null : request.operator(), idempotencyKey,
                    request == null ? null : request.reason(), ex);
            throw ex;
        }
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Map<String, Object> runSettlements(String idempotencyKey, ReferralSettlementRunRequest request) {
        try {
            validateIdempotency(idempotencyKey);
            String reason = requireReason(request == null ? null : request.reason());
            if (!A2ReplayContext.isReplaying()) {
                throw new BizException(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(),
                        "A2_CONFIRMATION_REQUIRED");
            }
            int limit = Math.min(100, Math.max(
                    1,
                    request == null || request.limit() == null ? 20 : request.limit()));
            // The database mutex survives until commit, serializes all H8 batches across
            // instances, and also freezes the reward configuration for the whole batch.
            requireRewardMutex();
            RewardSnapshot snapshot = rewardSnapshot();
            requireApprovedSnapshot(request, snapshot);
            return idempotency.execute("REFERRAL_REWARD_SETTLEMENT", idempotencyKey,
                    hash(limit + ":" + snapshot.hash() + ":" + reason), Map.class,
                    () -> settle(limit, reason, actor(request.operator()), idempotencyKey, snapshot));
        } catch (RuntimeException ex) {
            rejectedAudit("REFERRAL_REWARD_SETTLEMENT_RUN_REJECTED", "batch",
                    request == null ? null : request.operator(), idempotencyKey,
                    request == null ? null : request.reason(), ex);
            throw ex;
        }
    }

    private Map<String, Object> settle(
            int limit,
            String reason,
            String operator,
            String key,
            RewardSnapshot snapshot) {
        requireHealthyCoverage();
        LocalDateTime effectiveAt = snapshot.effectiveAt();
        EffectiveRewards effectiveRewards = snapshot.rewards();
        BigDecimal newcomerMultiplier = effectiveRewards.newcomerMultiplier();
        BigDecimal inviterMultiplier = effectiveRewards.inviterMultiplier();
        BigDecimal newcomerUsdt = effectiveRewards.newcomerUsdt();
        BigDecimal newcomerNex = effectiveRewards.newcomerNex();
        BigDecimal inviterNex = effectiveRewards.inviterNex();
        String lockMode = snapshot.lockMode();
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
                effectiveAmount(amount("newcomer.usdt"), newcomerMultiplier),
                effectiveAmount(amount("newcomer.nex"), newcomerMultiplier),
                effectiveAmount(amount("inviter.nex"), inviterMultiplier));
    }

    /** 钱包、结算表、D4 与 App/PC 展示共用六位小数，不把数据库隐式舍入当业务规则。 */
    private BigDecimal effectiveAmount(BigDecimal base, BigDecimal multiplier) {
        BigDecimal effective = base.multiply(multiplier).setScale(6, RoundingMode.HALF_UP);
        if (effective.signum() < 0 || effective.compareTo(MAX_EFFECTIVE_REWARD) > 0) {
            throw new BizException(
                    OpsErrorCode.VALIDATION_FAILED.httpStatus(),
                    "REFERRAL_REWARD_EFFECTIVE_AMOUNT_OVERFLOW");
        }
        return effective;
    }

    private record EffectiveRewards(
            int rhythmMonth,
            BigDecimal newcomerMultiplier,
            BigDecimal inviterMultiplier,
            BigDecimal newcomerUsdt,
            BigDecimal newcomerNex,
            BigDecimal inviterNex) {
    }

    private record RewardSnapshot(
            long h8Version,
            LocalDateTime effectiveAt,
            String lockMode,
            EffectiveRewards rewards,
            String hash) {
    }

    private RewardSnapshot rewardSnapshot() {
        long currentVersion = version(config.activeValue(VERSION_KEY).orElse("1"));
        LocalDateTime effectiveAt = effectiveAtRequired();
        String currentLockMode = lockMode();
        EffectiveRewards rewards = effectiveRewards();
        return new RewardSnapshot(
                currentVersion,
                effectiveAt,
                currentLockMode,
                rewards,
                rewardSnapshotHash(currentVersion, effectiveAt, currentLockMode, rewards));
    }

    private String rewardSnapshotHash(
            long h8Version,
            LocalDateTime effectiveAt,
            String currentLockMode,
            EffectiveRewards rewards) {
        String canonical = "h8Version=" + h8Version
                + "|effectiveAt=" + effectiveAt
                + "|lockMode=" + currentLockMode
                + "|rhythmMonth=" + rewards.rhythmMonth()
                + "|newcomerMultiplier=" + rewards.newcomerMultiplier().toPlainString()
                + "|inviterMultiplier=" + rewards.inviterMultiplier().toPlainString()
                + "|newcomerUsdt=" + rewards.newcomerUsdt().toPlainString()
                + "|newcomerNex=" + rewards.newcomerNex().toPlainString()
                + "|inviterNex=" + rewards.inviterNex().toPlainString();
        return hash(canonical);
    }

    private void requireApprovedSnapshot(
            ReferralSettlementRunRequest request,
            RewardSnapshot current) {
        if (request == null
                || request.expectedH8Version() == null
                || request.expectedRhythmMonth() == null
                || !StringUtils.hasText(request.rewardSnapshotHash())
                || request.expectedH8Version().longValue() != current.h8Version()
                || request.expectedRhythmMonth().intValue() != current.rewards().rhythmMonth()
                || !current.hash().equalsIgnoreCase(request.rewardSnapshotHash().trim())) {
            throw new BizException(409, "H8_REWARD_SNAPSHOT_CHANGED_REPROPOSE");
        }
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
                    : "newcomer.nex".equals(key) ? new BigDecimal("500") : MAX_INVITER_BASE;
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
        String raw = config.activeValue(EFFECTIVE_AT_KEY).orElseThrow(() ->
                new BizException(
                        OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(),
                        "REFERRAL_REWARD_EFFECTIVE_AT_MISSING"));
        try {
            return LocalDateTime.ofInstant(Instant.parse(raw), ZoneOffset.UTC);
        } catch (RuntimeException ex) {
            throw new BizException(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "REFERRAL_REWARD_EFFECTIVE_AT_INVALID");
        }
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

    private long requireExpectedVersion(Long expectedVersion) {
        if (expectedVersion == null || expectedVersion < 1) {
            throw new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "H8_EXPECTED_VERSION_REQUIRED");
        }
        return expectedVersion;
    }

    private long version(String raw) {
        try {
            long parsed = Long.parseLong(raw == null ? "" : raw.trim());
            if (parsed < 1 || parsed == Long.MAX_VALUE) throw new NumberFormatException();
            return parsed;
        } catch (RuntimeException ex) {
            throw new BizException(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(),
                    "H8_CONFIG_VERSION_INVALID");
        }
    }

    private void rejectedAudit(
            String action,
            String resourceId,
            String operator,
            String idempotencyKey,
            String reason,
            RuntimeException error) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", StringUtils.hasText(reason) ? reason.trim() : "");
        detail.put("idempotencyKey", StringUtils.hasText(idempotencyKey) ? idempotencyKey.trim() : "");
        detail.put("error", StringUtils.hasText(error.getMessage())
                ? error.getMessage()
                : error.getClass().getSimpleName());
        audit.recordRequiredInNewTransaction(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType("REFERRAL_REWARD")
                .resourceId(StringUtils.hasText(resourceId) ? resourceId.trim() : "unknown")
                .actorUsername(actor(operator))
                .result("REJECTED")
                .riskLevel("HIGH")
                .detail(detail)
                .build());
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
