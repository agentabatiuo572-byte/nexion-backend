package ffdd.opsconsole.finance.application;

import com.fasterxml.jackson.annotation.JsonFormat;
import ffdd.opsconsole.finance.mapper.EarningsReleaseMapper;
import ffdd.opsconsole.finance.mapper.EarningsReleaseMapper.BucketAmount;
import ffdd.opsconsole.finance.mapper.EarningsReleaseMapper.ProtectedEntry;
import ffdd.opsconsole.finance.mapper.EarningsReleaseMapper.RiskCluster;
import ffdd.opsconsole.risk.application.RiskReleaseParamsService;
import ffdd.opsconsole.risk.dto.EarningsManualReleaseRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import java.util.Locale;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class EarningsReleaseService {
    private static final Set<String> BUCKETS = Set.of("withdrawable", "pending_review", "bonus_locked");
    private static final Set<String> ASSETS = Set.of("USDT", "NEX");
    private static final Set<String> SOURCE_ENVIRONMENTS = Set.of("PRODUCTION", "SANDBOX");
    // Other domains have their own reversal owners. Do not let two owners recover the same source.
    private static final Set<String> RECOVERY_SOURCES = Set.of("PROMOTION_REWARD", "MOCK_PROMOTION_REWARD");
    private final EarningsReleaseMapper mapper;
    private final RiskReleaseParamsService params;
    private final AdminIdempotencyService idempotency;
    private final AuditLogService audit;
    private final FundsSandboxProfileGuard sandboxProfile;

    public record RewardRecoveryRequest(Long userId, String entryNo, String sourceType, String sourceRef,
            String asset, String sourceEnvironment, BigDecimal amount, String reason, String actor) { }

    public record RewardRecoveryReceipt(String recoveryNo, String entryNo, Long userId, String asset,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal requested,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal recovered,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal outstanding,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal cumulativeRecovered,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal remainingEntitlement, String bucket,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal balanceAfter, String evidenceStatus) { }

    /**
     * Internal asset primitive. The caller validates the durable refund/correction authority, and posts
     * D4 plus the original obligation in this same transaction. No public controller accepts this DTO.
     * A wallet debit watermark is deliberately conservative: every later debit may have spent this
     * reward; later deposits, other rewards and thawed reservations never restore its evidence.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY,
            rollbackFor = Exception.class)
    public RewardRecoveryReceipt recoverReward(RewardRecoveryRequest request, String idempotencyKey) {
        RewardRecoveryRequest command = normalizeRecovery(request);
        int sandbox = expectedWalletSandbox(command.sourceEnvironment());
        String scope = "EARNINGS_RECOVERY:" + command.sourceEnvironment() + ":" + command.userId();
        // Length-prefixed fields avoid delimiter collisions without a second serialization system.
        String payload = List.of(command.userId().toString(), command.entryNo(), command.sourceType(),
                command.sourceRef(), command.asset(), command.sourceEnvironment(), command.amount().toPlainString(),
                command.reason(), command.actor()).stream().map(v -> v.length() + ":" + v)
                .collect(java.util.stream.Collectors.joining());
        return idempotency.executeRetained(scope, idempotencyKey, hash(payload), RewardRecoveryReceipt.class,
                () -> recoverRewardOnce(command, sandbox));
    }

    private RewardRecoveryRequest normalizeRecovery(RewardRecoveryRequest request) {
        if (request == null || request.userId() == null || request.userId() <= 0
                || !recoveryText(request.entryNo(), 64) || !recoveryText(request.sourceType(), 64)
                || !recoveryText(request.sourceRef(), 128) || !recoveryText(request.reason(), 2000)
                || request.reason().trim().length() < 8 || !recoveryText(request.actor(), 128)
                || request.asset() == null || request.sourceEnvironment() == null
                || request.amount() == null || request.amount().signum() <= 0
                || request.amount().stripTrailingZeros().scale() > 6
                || request.amount().compareTo(new BigDecimal("999999999999.999999")) > 0) {
            throw new BizException(422, "EARNINGS_RECOVERY_REQUEST_INVALID");
        }
        String asset = request.asset().trim().toUpperCase(Locale.ROOT);
        String environment = request.sourceEnvironment().trim().toUpperCase(Locale.ROOT);
        if (!ASSETS.contains(asset) || !SOURCE_ENVIRONMENTS.contains(environment)
                || !RECOVERY_SOURCES.contains(request.sourceType().trim())
                || ("SANDBOX".equals(environment) != request.sourceType().trim().startsWith("MOCK_"))) {
            throw new BizException(422, "EARNINGS_RECOVERY_REQUEST_INVALID");
        }
        return new RewardRecoveryRequest(request.userId(), request.entryNo().trim(), request.sourceType().trim(),
                request.sourceRef().trim(), asset, environment, request.amount().setScale(6),
                request.reason().trim(), request.actor().trim());
    }

    private boolean recoveryText(String value, int max) {
        return StringUtils.hasText(value) && value.trim().length() <= max;
    }

    private RewardRecoveryReceipt recoverRewardOnce(RewardRecoveryRequest command, int sandbox) {
        // Account -> wallet -> source; the same order as credit and withdrawal. Frozen accounts may
        // be recovered, but never released or made withdrawable by this primitive.
        if (mapper.lockRecoveryUser(command.userId(), sandbox) == null) {
            throw new BizException(409, "EARNINGS_RECOVERY_ACCOUNT_MISSING");
        }
        var wallet = mapper.lockRecoveryWallet(command.userId(), sandbox);
        if (wallet == null) throw new BizException(409, "EARNINGS_RECOVERY_WALLET_MISSING");
        var entry = mapper.lockRecoveryEntry(command.entryNo());
        if (entry == null || !command.userId().equals(entry.userId())
                || !command.sourceType().equals(entry.sourceType()) || !command.sourceRef().equals(entry.sourceRef())
                || !command.asset().equals(entry.asset()) || !command.sourceEnvironment().equals(entry.sourceEnvironment())
                || !Integer.valueOf(0).equals(entry.isDeleted()) || !"ACTIVE".equals(entry.status())
                || !BUCKETS.contains(entry.bucket())) {
            throw new BizException(409, "EARNINGS_RECOVERY_SOURCE_MISMATCH");
        }
        BigDecimal remaining = entry.amount().subtract(entry.recoveredAmount());
        if (command.amount().compareTo(remaining) > 0) {
            throw new BizException(409, "EARNINGS_RECOVERY_EXCEEDS_ORIGINAL");
        }
        BigDecimal available = "USDT".equals(entry.asset()) ? wallet.usdtAvailable() : wallet.nexAvailable();
        BigDecimal debited = "USDT".equals(entry.asset()) ? wallet.usdtDebited() : wallet.nexDebited();
        boolean tracked = entry.debitBaseline() != null;
        if (available == null || available.signum() < 0 || debited == null || debited.signum() < 0
                || (tracked && (debited.compareTo(entry.debitBaseline()) < 0
                    || !wallet.walletId().equals(entry.sourceWalletId())))) {
            throw new BizException(409, "EARNINGS_RECOVERY_EVIDENCE_INVALID");
        }
        // A proven recovery from a different source is not consumption of this entry.
        // Keep this entry's earlier recoveries in the watermark; never exclude ordinary debits.
        BigDecimal otherRecoveries = tracked
                ? mapper.otherSourceRecoveriesSinceCredit(command.userId(), wallet.walletId(), entry.asset(),
                    entry.sourceEnvironment(), entry.entryNo(), entry.debitBaseline(), debited)
                    .stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                : BigDecimal.ZERO;
        BigDecimal debitDelta = tracked ? debited.subtract(entry.debitBaseline()) : BigDecimal.ZERO;
        if (otherRecoveries.signum() < 0 || otherRecoveries.compareTo(debitDelta) > 0) {
            throw new BizException(409, "EARNINGS_RECOVERY_EVIDENCE_INVALID");
        }
        BigDecimal provable = tracked
                ? entry.amount().subtract(debitDelta.subtract(otherRecoveries)).max(BigDecimal.ZERO)
                : BigDecimal.ZERO;
        BigDecimal recovered = command.amount().min(remaining).min(provable).min(available).setScale(6);
        if (recovered.signum() > 0) {
            if (mapper.debitRecoveredReward(command.userId(), entry.asset(), recovered, sandbox) != 1) {
                throw new BizException(409, "EARNINGS_RECOVERY_WALLET_CONFLICT");
            }
            if (mapper.addRecoveredAmount(entry.entryNo(), entry.recoveredAmount(), recovered) != 1) {
                throw new BizException(409, "EARNINGS_RECOVERY_ENTRY_CONFLICT");
            }
        }
        String recoveryNo = "ERC-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        var receipt = new RewardRecoveryReceipt(recoveryNo, entry.entryNo(), command.userId(), entry.asset(),
                command.amount(), recovered, command.amount().subtract(recovered), entry.recoveredAmount().add(recovered),
                remaining.subtract(recovered), entry.bucket(), available.subtract(recovered),
                tracked ? "TRACKED_CONSERVATIVE" : "LEGACY_UNPROVEN");
        if (mapper.insertRecovery(new EarningsReleaseMapper.RecoveryWrite(receipt, command.sourceType(),
                command.sourceRef(), command.sourceEnvironment(), command.reason(), command.actor(),
                available, debited, entry.debitBaseline())) != 1) {
            throw new BizException(409, "EARNINGS_RECOVERY_RECEIPT_CONFLICT");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("receipt", receipt); detail.put("sourceType", entry.sourceType());
        detail.put("sourceRef", entry.sourceRef()); detail.put("sourceEnvironment", entry.sourceEnvironment());
        detail.put("reason", command.reason()); detail.put("walletBefore", available);
        detail.put("debitCounterBefore", debited); detail.put("sourceDebitBaseline", entry.debitBaseline());
        detail.put("otherSourceRecoveriesExcluded", otherRecoveries);
        audit.recordRequiredForTrustedActor(AuditLogWriteRequest.builder().action("EARNINGS_SOURCE_RECOVERED")
                .resourceType("EARNINGS_RELEASE_ENTRY").resourceId(entry.entryNo()).bizNo(recoveryNo)
                .userId(command.userId()).actorUsername(command.actor()).riskLevel("HIGH").detail(detail).build());
        return receipt;
    }

    /** Single server-side entry point for every newly issued reward. */
    @Transactional(rollbackFor = Exception.class)
    public String creditReward(Long userId, String sourceType, String sourceRef, String asset,
                               BigDecimal amount, String idempotencyKey) {
        return creditReward(userId, sourceType, sourceRef, asset, amount, "PRODUCTION", idempotencyKey);
    }

    @Transactional(rollbackFor = Exception.class)
    public String creditReward(Long userId, String sourceType, String sourceRef, String asset,
                               BigDecimal amount, String sourceEnvironment, String idempotencyKey) {
        EarningsReleaseMapper.RiskCluster cluster = mapper.riskCluster(userId);
        String clusterId = cluster == null || !StringUtils.hasText(cluster.clusterId())
                ? "USER:" + userId : cluster.clusterId();
        int accounts = cluster == null || cluster.accountCount() == null ? 1 : cluster.accountCount();
        String status = cluster == null ? "" : String.valueOf(cluster.status()).toLowerCase();
        String bucket = Set.of("detected", "flagged", "frozen").contains(status) || accounts >= params.freezeFrom()
                ? "bonus_locked"
                : accounts >= params.pendingFrom() ? "pending_review" : "withdrawable";
        return credit(userId, clusterId, sourceType, sourceRef, asset, amount, bucket,
                sourceEnvironment, idempotencyKey);
    }

    @Transactional(rollbackFor = Exception.class)
    public String credit(Long userId, String clusterId, String sourceType, String sourceRef, String asset,
                         BigDecimal amount, String bucket, String idempotencyKey) {
        return credit(userId, clusterId, sourceType, sourceRef, asset, amount, bucket,
                "PRODUCTION", idempotencyKey);
    }

    @Transactional(rollbackFor = Exception.class)
    public String credit(Long userId, String clusterId, String sourceType, String sourceRef, String asset,
                         BigDecimal amount, String bucket, String sourceEnvironment, String idempotencyKey) {
        String normalizedAsset = asset == null ? "" : asset.trim().toUpperCase();
        String normalizedEnvironment = sourceEnvironment == null ? "" : sourceEnvironment.trim().toUpperCase();
        String normalizedSourceType = sourceType == null ? "" : sourceType.trim();
        boolean mockSource = normalizedSourceType.toUpperCase().startsWith("MOCK");
        if (userId == null || !StringUtils.hasText(clusterId) || !StringUtils.hasText(sourceType)
                || !StringUtils.hasText(sourceRef) || !StringUtils.hasText(idempotencyKey)
                || amount == null || amount.signum() <= 0 || !BUCKETS.contains(bucket)
                || !ASSETS.contains(normalizedAsset) || !SOURCE_ENVIRONMENTS.contains(normalizedEnvironment)
                || ("SANDBOX".equals(normalizedEnvironment) != mockSource)) {
            throw new BizException(422, "EARNINGS_RELEASE_ENTRY_INVALID");
        }
        String normalizedSourceRef = sourceRef.trim();
        String normalizedIdempotencyKey = idempotencyKey.trim();
        int expectedSandbox = expectedWalletSandbox(normalizedEnvironment);
        String no = "ER-" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        // Follow the withdrawal order explicitly: user, wallet, then earnings entry.
        mapper.lockCreditUser(userId, expectedSandbox);
        mapper.lockCreditWallet(userId, expectedSandbox);
        if (mapper.insert(new EarningsReleaseMapper.EntryWrite(no, userId, clusterId, normalizedSourceType,
                normalizedSourceRef, normalizedAsset, amount, bucket, normalizedIdempotencyKey, normalizedEnvironment)) != 1) {
            EarningsReleaseMapper.ExistingEntry existing = mapper.findBySource(
                    normalizedSourceType, normalizedSourceRef, userId);
            if (!matchesExistingEntry(existing, userId, normalizedSourceType, normalizedSourceRef,
                    normalizedAsset, amount, normalizedEnvironment, normalizedIdempotencyKey)) {
                throw new BizException(409, "EARNINGS_RELEASE_ENTRY_CONFLICT");
            }
            return existing.entryNo();
        }
        int credited = "USDT".equals(normalizedAsset)
                ? mapper.creditUsdt(userId, amount, normalizedEnvironment, expectedSandbox)
                : mapper.creditNex(userId, amount, normalizedEnvironment, expectedSandbox);
        if (credited != 1) throw new BizException(409, "EARNINGS_RELEASE_WALLET_CONFLICT");
        return no;
    }

    private int expectedWalletSandbox(String sourceEnvironment) {
        if (sandboxProfile != null && sandboxProfile.isStrictProductionRuntime()) {
            if (!"PRODUCTION".equals(sourceEnvironment)) {
                throw new BizException(422, "EARNINGS_RELEASE_ENVIRONMENT_INVALID");
            }
            return 0;
        }
        if (sandboxProfile != null && sandboxProfile.isStrictTestRuntime()) {
            if (!"SANDBOX".equals(sourceEnvironment)) {
                throw new BizException(422, "EARNINGS_RELEASE_ENVIRONMENT_INVALID");
            }
            return 1;
        }
        throw new BizException(503, "EARNINGS_RELEASE_PROFILE_INVALID");
    }

    private boolean matchesExistingEntry(EarningsReleaseMapper.ExistingEntry existing, Long userId,
                                         String sourceType, String sourceRef, String asset, BigDecimal amount,
                                         String sourceEnvironment, String idempotencyKey) {
        return existing != null
                && Integer.valueOf(0).equals(existing.isDeleted())
                && "ACTIVE".equals(existing.status())
                && userId.equals(existing.userId())
                && sourceType.equals(existing.sourceType())
                && sourceRef.equals(existing.sourceRef())
                && asset.equals(existing.asset())
                && amount.compareTo(existing.amount()) == 0
                && sourceEnvironment.equals(existing.sourceEnvironment())
                && idempotencyKey.equals(existing.idempotencyKey());
    }

    public ApiResult<Map<String, Object>> status(Long userId) {
        Map<String, Map<String, BigDecimal>> assets = new LinkedHashMap<>();
        for (String asset : ASSETS) {
            Map<String, BigDecimal> buckets = new LinkedHashMap<>();
            BUCKETS.forEach(bucket -> buckets.put(bucket, BigDecimal.ZERO));
            assets.put(asset, buckets);
        }
        for (BucketAmount row : mapper.buckets(userId)) {
            assets.computeIfAbsent(row.asset(), ignored -> new LinkedHashMap<>()).put(row.bucket(), row.amount());
        }
        RiskCluster cluster = mapper.riskCluster(userId);
        if (clusterRestricted(cluster)) {
            for (Map<String, BigDecimal> buckets : assets.values()) {
                BigDecimal withdrawable = buckets.getOrDefault("withdrawable", BigDecimal.ZERO);
                buckets.put("withdrawable", BigDecimal.ZERO);
                buckets.put("bonus_locked", buckets.getOrDefault("bonus_locked", BigDecimal.ZERO).add(withdrawable));
            }
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("buckets", assets.get("USDT"));
        response.put("assets", assets);
        response.put("releaseMode", params.manualOnly() ? "manual_only" : "attest_or_manual");
        response.put("attestedOnlineSeconds", mapper.attestedSeconds(userId,"PRODUCTION"));
        response.put("requiredAttestationSeconds", params.attestationHours() * 3600L);
        response.put("serverCanonical", true);
        response.put("clusterRestricted", clusterRestricted(cluster));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> protectedEntries(Long userId, String clusterId, Integer limit) {
        int normalizedLimit = Math.max(1, Math.min(limit == null ? 100 : limit, 200));
        String normalizedCluster = StringUtils.hasText(clusterId) ? clusterId.trim() : null;
        List<Map<String,Object>> items = mapper.protectedEntryViews(userId, normalizedCluster, normalizedLimit)
                .stream().map(row -> {
                    Map<String,Object> item = new LinkedHashMap<>();
                    item.put("entryNo", row.entryNo());
                    item.put("userId", row.userId());
                    item.put("clusterId", row.clusterId());
                    item.put("sourceType", row.sourceType());
                    item.put("sourceRef", row.sourceRef());
                    item.put("asset", row.asset());
                    item.put("amount", row.amount());
                    item.put("bucket", row.bucket());
                    item.put("createdAt", row.createdAt());
                    return item;
                }).toList();
        return ApiResult.ok(Map.of("items", items, "limit", normalizedLimit, "serverCanonical", true));
    }

    public record TrustedAttestationProof(String proofId,Long userId,String deviceId,Long commandVersion,
                                          String appliedStatus,String source,String proofHash) { }

    /** Only a claimed, persisted Janus executor proof may mint trusted online time. */
    @Transactional(rollbackFor = Exception.class)
    public void recordTrustedAttestation(TrustedAttestationProof proof) {
        if (proof == null || proof.userId() == null || proof.commandVersion() == null
                || !StringUtils.hasText(proof.proofId()) || !StringUtils.hasText(proof.deviceId())
                || !StringUtils.hasText(proof.proofHash()) || !"JANUS_PRODUCTION_EXECUTOR".equals(proof.source())) return;
        Long userId=proof.userId(); String deviceId=proof.deviceId().trim();
        String sourceEnvironment="PRODUCTION";
        if (userId == null || !StringUtils.hasText(deviceId) || params.manualOnly()) return;
        if(mapper.proofIdentityMatches(userId,proof.source())!=1)return;
        if (params.freeSlotRequiresBinding() && mapper.trustedDeviceBinding(userId, deviceId.trim()) != 1) return;
        if (mapper.consumeAppliedProof(proof.proofId().trim(),userId,deviceId,proof.commandVersion(),proof.source(),proof.proofHash().trim()) != 1) return;
        if (mapper.recordAttestation(userId, deviceId.trim(), sourceEnvironment) < 1) {
            throw new BizException(409, "EARNINGS_ATTESTATION_CONFLICT");
        }
        if (mapper.attestedSeconds(userId, sourceEnvironment) < params.attestationHours() * 3600L) return;
        mapper.lockCreditUser(userId, 0);
        mapper.lockCreditWallet(userId, 0);
        Set<String> lockedClusters = new TreeSet<>();
        for (ProtectedEntry scope : mapper.protectedEntryScopes(userId, sourceEnvironment)) {
            String cluster = StringUtils.hasText(scope.clusterId()) ? scope.clusterId() : "USER:" + userId;
            if (!cluster.startsWith("USER:")) lockedClusters.add(cluster);
        }
        for (String cluster : lockedClusters) {
            if (mapper.lockCluster(cluster) == null) {
                throw new BizException(409, "EARNINGS_RELEASE_CLUSTER_MISSING");
            }
        }
        for (ProtectedEntry entry : mapper.protectedEntries(userId, sourceEnvironment)) {
            String cluster = StringUtils.hasText(entry.clusterId()) ? entry.clusterId() : "USER:" + userId;
            if (!cluster.startsWith("USER:") && !lockedClusters.contains(cluster)) {
                throw new BizException(409, "EARNINGS_RELEASE_CLUSTER_MISSING");
            }
            if (mapper.releasedAccountsInWindow(cluster, userId, params.releaseWindowHours()) >= params.freeSlots()) continue;
            int released=mapper.releaseFromJanusProof(entry.entryNo(),proof.source());
            if(released!=1)throw new BizException(409,"EARNINGS_RELEASE_CONFLICT");
            audit.recordRequiredForTrustedActor(AuditLogWriteRequest.builder()
                    .action("K1_EARNINGS_ATTESTATION_RELEASED")
                    .resourceType("EARNINGS_RELEASE_ENTRY").resourceId(entry.entryNo())
                    .actorUsername("janus-carrier").riskLevel("HIGH")
                    .detail(Map.of("userId", userId, "clusterId", cluster, "deviceId", deviceId.trim(),
                            "before", entry.bucket(), "after", "withdrawable", "source", proof.source()))
                    .build());
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public ApiResult<Map<String, Object>> manualRelease(String entryNo, String idempotencyKey,
                                                         EarningsManualReleaseRequest request) {
        if (!StringUtils.hasText(entryNo) || !StringUtils.hasText(idempotencyKey) || request == null
                || !StringUtils.hasText(request.reason()) || request.reason().trim().length() < 8) {
            throw new BizException(422, "EARNINGS_MANUAL_RELEASE_REQUEST_INVALID");
        }
        String hash = hash(entryNo.trim() + "|" + request.reason().trim() + "|" + request.operator());
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotency.execute(
                "K1_EARNINGS_MANUAL_RELEASE:" + entryNo.trim(), idempotencyKey.trim(), hash,
                ApiResult.class, (Supplier) () -> manualReleaseOnce(entryNo.trim(), request));
    }

    @Transactional(rollbackFor = Exception.class)
    protected ApiResult<Map<String, Object>> manualReleaseOnce(String entryNo,
                                                                EarningsManualReleaseRequest request) {
        ProtectedEntry entry = mapper.lockProtectedEntry(entryNo);
        if (entry == null) throw new BizException(409, "EARNINGS_RELEASE_ENTRY_NOT_PROTECTED");
        TreasuryCoverageSnapshot coverage = params.requireCoverageForAmplifyingRelease();
        if (mapper.release(entryNo, "manual") != 1) throw new BizException(409, "EARNINGS_RELEASE_CONFLICT");
        String actor = AdminActorResolver.resolve(request.operator());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("userId", entry.userId());
        detail.put("clusterId", entry.clusterId());
        detail.put("asset", entry.asset());
        detail.put("amount", entry.amount());
        detail.put("before", entry.bucket());
        detail.put("after", "withdrawable");
        detail.put("reason", request.reason().trim());
        detail.put("coverageAtSubmit", coverage.coverageRatio());
        detail.put("coverageRedline", coverage.redlinePct());
        audit.recordRequired(AuditLogWriteRequest.builder().action("K1_EARNINGS_MANUALLY_RELEASED")
                .resourceType("EARNINGS_RELEASE_ENTRY").resourceId(entryNo).actorUsername(actor)
                .riskLevel("HIGH").detail(detail).build());
        return ApiResult.ok(Map.of("entryNo", entryNo, "bucket", "withdrawable", "releaseSource", "manual"));
    }

    public void assertWithdrawable(Long userId, BigDecimal walletAvailable, BigDecimal requested) {
        if (clusterRestricted(mapper.riskCluster(userId))) {
            throw new BizException(409, "WITHDRAWAL_CLUSTER_RESTRICTED");
        }
        if (withdrawableAmount(userId, walletAvailable).compareTo(requested) < 0) {
            throw new BizException(409, "WITHDRAWAL_RELEASE_BUCKET_INSUFFICIENT");
        }
    }

    /** Read-only preflight; submission re-reads current rows with locks. */
    public BigDecimal withdrawableAmount(Long userId, BigDecimal walletAvailable) {
        if (clusterRestricted(mapper.riskCluster(userId))) return BigDecimal.ZERO;
        BigDecimal protectedAmount = mapper.protectedAmount(userId);
        if (protectedAmount == null) protectedAmount = BigDecimal.ZERO;
        return walletAvailable.subtract(protectedAmount.max(BigDecimal.ZERO)).max(BigDecimal.ZERO);
    }

    /** Locks remain held by the withdrawal transaction through wallet reservation. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public BigDecimal withdrawableAmountForUpdate(Long userId, BigDecimal walletAvailable) {
        if (clusterRestricted(mapper.lockRiskCluster(userId))) {
            throw new BizException(409, "WITHDRAWAL_CLUSTER_RESTRICTED");
        }
        BigDecimal protectedAmount = mapper.lockProtectedUsdtAmounts(userId).stream()
                .map(amount -> amount.max(BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add);
        return walletAvailable.subtract(protectedAmount).max(BigDecimal.ZERO);
    }

    private boolean clusterRestricted(RiskCluster cluster) {
        if (cluster == null) return false;
        String status = String.valueOf(cluster.status()).trim().toLowerCase();
        return Set.of("detected", "flagged", "frozen").contains(status)
                || (cluster.accountCount() != null && cluster.accountCount() >= params.freezeFrom());
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
