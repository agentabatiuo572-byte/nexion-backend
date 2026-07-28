package ffdd.opsconsole.treasury.infrastructure;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerBillView;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerRepository;
import ffdd.opsconsole.treasury.mapper.TreasuryLedgerMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class MybatisTreasuryLedgerRepository implements TreasuryLedgerRepository {
    private final TreasuryLedgerMapper mapper;
    private final EventOutboxService outboxService;

    @Override
    public long countDeposits(LocalDateTime since, String status) {
        return mapper.countDeposits(since, status);
    }

    @Override
    public long countWithdrawals(LocalDateTime since, String status) {
        return mapper.countWithdrawals(since, status);
    }

    @Override
    public long countExchanges(LocalDateTime since, String status) {
        return mapper.countExchanges(since, status);
    }

    @Override
    public long countLedgers(LocalDateTime since, String direction) {
        return mapper.countLedgers(since, direction);
    }

    @Override
    public BigDecimal sumUsdtAvailable() {
        return nz(mapper.sumUsdtAvailable());
    }

    @Override
    public BigDecimal sumPendingWithdraw() {
        return nz(mapper.sumPendingWithdraw());
    }

    @Override
    public BigDecimal sumNexAvailable() {
        return nz(mapper.sumNexAvailable());
    }

    @Override
    public BigDecimal legacyLockOtherLiabilityUsd() {
        return nz(mapper.legacyLockOtherLiabilityUsd());
    }

    @Override
    public BigDecimal pendingUnverifiedDepositUsdt() {
        return nz(mapper.pendingUnverifiedDepositUsdt());
    }

    @Override
    public BigDecimal vietQrHeldReserveUsdt() {
        return nz(mapper.vietQrHeldReserveUsdt());
    }

    @Override
    public BigDecimal sumActiveStakingPrincipalUsdt() {
        return nz(mapper.sumActiveStakingPrincipalUsdt());
    }

    @Override
    public BigDecimal sumActiveStakingInterestUsdt() {
        return nz(mapper.sumActiveStakingInterestUsdt());
    }

    @Override
    public BigDecimal sumActiveNexLocked() {
        return nz(mapper.sumActiveNexLocked());
    }

    @Override
    public BigDecimal sumActiveNexReward() {
        return nz(mapper.sumActiveNexReward());
    }

    @Override
    public BigDecimal sumActiveWithdrawalQueueUsdt() {
        return nz(mapper.sumActiveWithdrawalQueueUsdt());
    }

    @Override
    public BigDecimal sumWithdrawalRequested24hUsdt() {
        return nz(mapper.sumWithdrawalRequested24hUsdt());
    }

    @Override
    public long countActiveWithdrawalQueue() {
        return mapper.countActiveWithdrawalQueue();
    }

    @Override
    public BigDecimal avgActiveWithdrawalQueueRiskScore() {
        return nz(mapper.avgActiveWithdrawalQueueRiskScore());
    }

    @Override
    public BigDecimal sumPendingCommissionUsdt() {
        return nz(mapper.sumPendingCommissionUsdt());
    }

    @Override
    public BigDecimal sumNetUsdtFlowBetween(LocalDateTime startAt, LocalDateTime endAt) {
        return nz(mapper.sumNetUsdtFlowBetween(startAt, endAt));
    }

    @Override
    public List<Map<String, Object>> maturityBuckets(LocalDateTime startAt, LocalDateTime endAt) {
        return mapper.maturityBuckets(startAt, endAt, 30, "LINEAR");
    }

    @Override
    public List<Map<String, Object>> maturityBuckets(
            LocalDateTime startAt, LocalDateTime endAt, int withdrawCooldownDays, String interestMode) {
        return mapper.maturityBuckets(startAt, endAt, withdrawCooldownDays, interestMode);
    }

    @Override
    public List<Map<String, Object>> trialStressBuckets(LocalDateTime startAt, LocalDateTime endAt) {
        return mapper.trialStressBuckets(startAt, endAt);
    }

    @Override
    public List<BigDecimal> riskPressureSeries(LocalDateTime since) {
        return mapper.riskPressureSeries(since).stream()
                .map(this::nz)
                .toList();
    }

    @Override
    public List<Map<String, Object>> riskRuleBuckets(LocalDateTime since) {
        return mapper.riskRuleBuckets(since);
    }

    @Override
    public List<Map<String, Object>> riskSeverityBuckets(LocalDateTime since) {
        return mapper.riskSeverityBuckets(since);
    }

    @Override
    public List<Map<String, Object>> riskVolumeBuckets(LocalDateTime since) {
        return mapper.riskVolumeBuckets(since);
    }

    @Override
    public Map<String, Object> currentK4RiskScoreSnapshot() {
        return Optional.ofNullable(mapper.currentK4RiskScoreSnapshot()).orElseGet(Map::of);
    }

    @Override
    public List<Map<String, Object>> recentK5KycAlerts(LocalDateTime since, int limit) {
        return Optional.ofNullable(mapper.recentK5KycAlerts(since, limit)).orElseGet(List::of);
    }

    @Override
    public BigDecimal currentReserveUsd() {
        return nz(mapper.currentReserveUsd());
    }

    @Override
    public BigDecimal injectedCumulativeUsd() {
        return nz(mapper.injectedCumulativeUsd());
    }

    @Override
    public BigDecimal genesisDailyLiabilityUsd() {
        return nz(mapper.genesisDailyLiabilityUsd());
    }

    @Override
    public boolean reserveVoucherExists(String voucherNo) {
        return mapper.countReserveVoucher(trim(voucherNo)) > 0;
    }

    @Override
    public BigDecimal walletLedgerReconciliationGapUsdt() {
        return nz(mapper.walletLedgerReconciliationGapUsdt());
    }

    @Override
    public Optional<BigDecimal> latestNexUsdtPrice() {
        return Optional.ofNullable(mapper.latestNexUsdtPrice()).map(this::nz);
    }

    @Override
    public void recordReserveInjection(String voucherNo, BigDecimal amountUsd, String reason, String operator, String idempotencyKey) {
        mapper.insertReserveInjection(
                "RSV-D3-" + java.util.UUID.randomUUID(),
                trim(voucherNo),
                nz(amountUsd).setScale(2, java.math.RoundingMode.HALF_UP),
                trim(reason),
                trim(operator),
                trim(idempotencyKey));
    }

    @Override
    public void recordWithdrawalReserve(
            String withdrawalNo, BigDecimal amountUsd, String reason, String operator, String idempotencyKey) {
        String safeWithdrawalNo = safeBiz(withdrawalNo);
        if (mapper.insertTopupReserveEntry(
                compactKey("RSV-WD-", safeWithdrawalNo, 64),
                compactKey("WD-", safeWithdrawalNo, 96),
                "OUT",
                nz(amountUsd).setScale(6, java.math.RoundingMode.UNNECESSARY),
                trim(reason),
                trim(operator),
                trim(idempotencyKey)) != 1) {
            throw new IllegalStateException("WITHDRAWAL_RESERVE_WRITE_FAILED");
        }
    }

    @Override
    @Transactional
    public void refundWithdrawal(String withdrawalNo, Long userId, BigDecimal amount, String asset, String reason) {
        refundWithdrawal(withdrawalNo, userId, amount, asset, BigDecimal.ZERO, reason);
    }

    @Override
    @Transactional
    public void refundWithdrawal(
            String withdrawalNo,
            Long userId,
            BigDecimal amount,
            String asset,
            BigDecimal nexBurned,
            String reason) {
        if (!"USDT".equalsIgnoreCase(trim(asset))) {
            throw new IllegalStateException("WITHDRAWAL_REFUND_ASSET_UNSUPPORTED");
        }
        BigDecimal safeAmount = nz(amount).abs().setScale(6, java.math.RoundingMode.UNNECESSARY);
        BigDecimal safeNexBurned = nz(nexBurned).abs().setScale(6, java.math.RoundingMode.UNNECESSARY);
        BigDecimal usdtBefore = actualUserBalance(userId, "USDT").orElse(BigDecimal.ZERO);
        BigDecimal nexBefore = actualUserBalance(userId, "NEX").orElse(BigDecimal.ZERO);
        if (mapper.releasePendingWithdrawalWithNex(userId, safeAmount, safeNexBurned) != 1) {
            throw new IllegalStateException("WITHDRAWAL_PENDING_FUNDS_INCONSISTENT");
        }
        insertImmutableLedgerEntry(
                compactKey("D2-REFUND-", safeBiz(withdrawalNo), 96),
                userId,
                "WITHDRAW_REFUND",
                "USDT",
                "IN",
                safeAmount,
                usdtBefore.add(safeAmount),
                "SUCCESS",
                trim(reason));
        if (safeNexBurned.signum() > 0) {
            insertImmutableLedgerEntry(
                    compactKey("D2-NEX-REFUND-", safeBiz(withdrawalNo), 96),
                    userId,
                    "WITHDRAW_FEE_OFFSET_REFUND",
                    "NEX",
                    "IN",
                    safeNexBurned,
                    nexBefore.add(safeNexBurned),
                    "SUCCESS",
                    trim(reason));
        }
    }

    @Override
    public void recordTopupReserve(String paymentNo, BigDecimal amountUsd, String eventId) {
        String safePaymentNo = safeBiz(paymentNo);
        if (mapper.insertTopupReserveEntry(
                compactKey("RSV-TOPUP-", safePaymentNo, 64),
                safePaymentNo,
                "IN",
                nz(amountUsd).setScale(6, java.math.RoundingMode.UNNECESSARY),
                "D1 card topup confirmed",
                "payment-gateway",
                trim(eventId)) != 1) {
            throw new IllegalStateException("TOPUP_RESERVE_WRITE_FAILED");
        }
    }

    @Override
    public void reverseTopupReserve(String paymentNo, BigDecimal amountUsd, String idempotencyKey) {
        String safePaymentNo = safeBiz(paymentNo);
        if (mapper.insertTopupReserveEntry(
                compactKey("RSV-CB-", safePaymentNo, 64),
                compactKey("CB-", safePaymentNo, 96),
                "OUT",
                nz(amountUsd).setScale(6, java.math.RoundingMode.UNNECESSARY),
                "D1 chargeback reserve reversal",
                "d1-chargeback",
                trim(idempotencyKey)) != 1) {
            throw new IllegalStateException("TOPUP_RESERVE_REVERSAL_FAILED");
        }
    }

    @Override
    public long countLedgerBills(String type, Long userId, String keyword) {
        return mapper.countLedgerBills(trim(type), userId, trim(keyword), null, null, null, null);
    }

    @Override
    public long countLedgerBills(String type, Long userId, String keyword, String bizNo) {
        return mapper.countLedgerBills(trim(type), userId, trim(keyword), trim(bizNo), null, null, null);
    }

    @Override
    public long countLedgerBills(String type, Long userId, String keyword, String bizNo,
                                 String status, java.time.LocalDateTime from, java.time.LocalDateTime to) {
        return mapper.countLedgerBills(trim(type), userId, trim(keyword), trim(bizNo), trim(status), from, to);
    }

    @Override
    public List<TreasuryLedgerBillView> pageLedgerBills(String type, Long userId, String keyword, int pageSize, int offset) {
        return mapper.pageLedgerBills(trim(type), userId, trim(keyword), null, null, null, null, pageSize, offset);
    }

    @Override
    public List<TreasuryLedgerBillView> pageLedgerBills(
            String type, Long userId, String keyword, String bizNo, int pageSize, int offset) {
        return mapper.pageLedgerBills(trim(type), userId, trim(keyword), trim(bizNo), null, null, null, pageSize, offset);
    }

    @Override
    public List<TreasuryLedgerBillView> pageLedgerBills(
            String type, Long userId, String keyword, String bizNo, String status,
            java.time.LocalDateTime from, java.time.LocalDateTime to, int pageSize, int offset) {
        return mapper.pageLedgerBills(trim(type), userId, trim(keyword), trim(bizNo), trim(status), from, to, pageSize, offset);
    }

    @Override
    public List<TreasuryLedgerBillView> userLedgerRows(Long userId, int limit) {
        return mapper.userLedgerRows(userId, limit);
    }

    @Override
    public Optional<BigDecimal> currentUserBalance(Long userId, String asset) {
        return Optional.ofNullable(mapper.currentUserBalance(userId, trim(asset))).map(this::nz);
    }

    @Override
    public Optional<BigDecimal> actualUserBalance(Long userId, String asset) {
        return Optional.ofNullable(mapper.actualUserBalance(userId, trim(asset))).map(this::nz);
    }

    @Override
    public boolean userExists(Long userId) {
        return userId != null && userId > 0 && mapper.countActiveUser(userId) > 0;
    }

    @Override
    @Transactional
    public void postLedgerEntry(String bizNo, Long userId, String bizType, String asset, String direction,
                                BigDecimal amount, String status, String remark) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("D4_LEDGER_USER_REQUIRED");
        }
        String normalizedBizNo = requiredUpperOrOriginal(bizNo, "D4_LEDGER_BIZ_NO_REQUIRED", false);
        String normalizedBizType = requiredUpperOrOriginal(bizType, "D4_LEDGER_BIZ_TYPE_REQUIRED", true);
        String normalizedAsset = requiredUpperOrOriginal(asset, "D4_LEDGER_ASSET_REQUIRED", true);
        String normalizedDirection = requiredUpperOrOriginal(direction, "D4_LEDGER_DIRECTION_REQUIRED", true);
        String normalizedStatus = requiredUpperOrOriginal(status, "D4_LEDGER_STATUS_REQUIRED", true);
        if (!java.util.Set.of("USDT", "NEX").contains(normalizedAsset)) {
            throw new IllegalArgumentException("D4_LEDGER_ASSET_INVALID");
        }
        if (!java.util.Set.of("IN", "OUT").contains(normalizedDirection)) {
            throw new IllegalArgumentException("D4_LEDGER_DIRECTION_INVALID");
        }
        if (!java.util.Set.of("PENDING", "SUCCESS", "POSTED", "COMPLETED", "CONFIRMED",
                "FAILED", "REJECTED", "CANCELLED").contains(normalizedStatus)) {
            throw new IllegalArgumentException("D4_LEDGER_STATUS_INVALID");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("D4_LEDGER_AMOUNT_MUST_BE_POSITIVE");
        }
        Long safeUserId = userId;
        BigDecimal safeAmount = amount.abs();
        String uniqueKey = "D4_BIZ_" + UUID.nameUUIDFromBytes(
                (normalizedBizNo + "|" + normalizedAsset + "|" + normalizedDirection)
                        .getBytes(StandardCharsets.UTF_8));
        mapper.ensureLedgerMutex(uniqueKey);
        if (!uniqueKey.equals(mapper.lockLedgerMutex(uniqueKey))) {
            throw new IllegalStateException("D4_LEDGER_IDEMPOTENCY_MUTEX_UNAVAILABLE");
        }
        String lockKey = "D4_LEDGER_" + safeUserId + "_" + normalizedAsset;
        mapper.ensureLedgerMutex(lockKey);
        if (!lockKey.equals(mapper.lockLedgerMutex(lockKey))) {
            throw new IllegalStateException("D4_LEDGER_MUTEX_UNAVAILABLE");
        }
        WalletLedgerEntity existing = mapper.findLedgerEntry(
                normalizedBizNo, normalizedAsset, normalizedDirection);
        if (existing != null) {
            assertSameLedgerFingerprint(
                    existing, safeUserId, normalizedBizType, safeAmount, normalizedStatus, trim(remark));
            return;
        }
        BigDecimal current = currentUserBalance(safeUserId, normalizedAsset).orElse(BigDecimal.ZERO);
        BigDecimal balanceAfter = "OUT".equals(normalizedDirection)
                ? current.subtract(safeAmount)
                : current.add(safeAmount);
        if (balanceAfter.signum() < 0) {
            throw new IllegalStateException("D4_LEDGER_INSUFFICIENT_BALANCE");
        }
        insertImmutableLedgerEntry(
                normalizedBizNo,
                safeUserId,
                normalizedBizType,
                normalizedAsset,
                normalizedDirection,
                safeAmount,
                balanceAfter,
                normalizedStatus,
                trim(remark));
    }

    private void insertImmutableLedgerEntry(
            String bizNo,
            Long userId,
            String bizType,
            String asset,
            String direction,
            BigDecimal amount,
            BigDecimal balanceAfter,
            String status,
            String remark) {
        WalletLedgerEntity existing = mapper.findLedgerEntry(bizNo, asset, direction);
        if (existing != null) {
            assertSameLedgerFingerprint(existing, userId, bizType, amount, status, remark);
            return;
        }
        int inserted;
        try {
            inserted = mapper.insertLedgerEntry(
                    bizNo, userId, bizType, asset, direction, amount, balanceAfter, status, remark);
        } catch (DuplicateKeyException duplicate) {
            existing = mapper.findLedgerEntry(bizNo, asset, direction);
            assertSameLedgerFingerprint(existing, userId, bizType, amount, status, remark);
            return;
        }
        if (inserted == 1) {
            outboxService.publish("WALLET_LEDGER", bizNo, "wallet.ledger_posted", Map.of(
                    "bizNo", bizNo,
                    "userId", userId,
                    "bizType", bizType,
                    "asset", asset,
                    "direction", direction,
                    "amount", amount,
                    "balanceAfter", balanceAfter,
                    "status", status));
            return;
        }
        existing = mapper.findLedgerEntry(bizNo, asset, direction);
        assertSameLedgerFingerprint(existing, userId, bizType, amount, status, remark);
    }

    private void assertSameLedgerFingerprint(
            WalletLedgerEntity existing,
            Long userId,
            String bizType,
            BigDecimal amount,
            String status,
            String remark) {
        if (existing == null
                || !java.util.Objects.equals(existing.getUserId(), userId)
                || !sameDecimal(existing.getAmount(), amount)
                || !java.util.Objects.equals(upper(existing.getBizType(), ""), upper(bizType, ""))
                || !java.util.Objects.equals(upper(existing.getStatus(), ""), upper(status, ""))
                || !java.util.Objects.equals(trim(existing.getRemark()), trim(remark))) {
            throw new IllegalStateException("D4_LEDGER_IDEMPOTENCY_CONFLICT");
        }
    }

    private boolean sameDecimal(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }

    private String requiredUpperOrOriginal(String value, String error, boolean uppercase) {
        String normalized = trim(value);
        if (normalized == null || normalized.isBlank()) {
            throw new IllegalArgumentException(error);
        }
        return uppercase ? normalized.toUpperCase(Locale.ROOT) : normalized;
    }

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private String upper(String value, String fallback) {
        String trimmed = trim(value);
        return trimmed == null || trimmed.isBlank()
                ? fallback
                : trimmed.toUpperCase(Locale.ROOT);
    }

    private String safeBiz(String value) {
        String normalized = trim(value);
        return normalized == null ? "UNKNOWN" : normalized.replaceAll("[^A-Za-z0-9_.-]", "-");
    }

    private String compactKey(String prefix, String value, int maxLength) {
        String candidate = prefix + value;
        if (candidate.length() <= maxLength) {
            return candidate;
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return prefix + java.util.HexFormat.of().formatHex(digest).substring(0, maxLength - prefix.length());
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA256_UNAVAILABLE", ex);
        }
    }
}
