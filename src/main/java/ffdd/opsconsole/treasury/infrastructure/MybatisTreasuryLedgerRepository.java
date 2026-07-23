package ffdd.opsconsole.treasury.infrastructure;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerBillView;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerRepository;
import ffdd.opsconsole.treasury.mapper.TreasuryLedgerMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class MybatisTreasuryLedgerRepository implements TreasuryLedgerRepository {
    private final TreasuryLedgerMapper mapper;

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
    public void refundWithdrawal(String withdrawalNo, Long userId, BigDecimal amount, String asset, String reason) {
        refundWithdrawal(withdrawalNo, userId, amount, asset, BigDecimal.ZERO, reason);
    }

    @Override
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
        if (mapper.insertLedgerEntry(
                compactKey("D2-REFUND-", safeBiz(withdrawalNo), 96),
                userId,
                "WITHDRAW_REFUND",
                "USDT",
                "IN",
                safeAmount,
                usdtBefore.add(safeAmount),
                "SUCCESS",
                trim(reason)) != 1) {
            throw new IllegalStateException("WITHDRAWAL_REFUND_LEDGER_WRITE_FAILED");
        }
        if (safeNexBurned.signum() > 0) {
            if (mapper.insertLedgerEntry(
                    compactKey("D2-NEX-REFUND-", safeBiz(withdrawalNo), 96),
                    userId,
                    "WITHDRAW_FEE_OFFSET_REFUND",
                    "NEX",
                    "IN",
                    safeNexBurned,
                    nexBefore.add(safeNexBurned),
                    "SUCCESS",
                    trim(reason)) != 1) {
                throw new IllegalStateException("WITHDRAWAL_NEX_REFUND_LEDGER_WRITE_FAILED");
            }
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
    @Transactional
    public void postLedgerEntry(String bizNo, Long userId, String bizType, String asset, String direction,
                                BigDecimal amount, String status, String remark) {
        Long safeUserId = userId == null ? 0L : userId;
        String normalizedAsset = upper(asset, "USDT");
        String normalizedDirection = upper(direction, "IN");
        BigDecimal safeAmount = amount == null ? BigDecimal.ZERO : amount.abs();
        String lockKey = "D4_LEDGER_" + safeUserId + "_" + normalizedAsset;
        mapper.ensureLedgerMutex(lockKey);
        if (!lockKey.equals(mapper.lockLedgerMutex(lockKey))) {
            throw new IllegalStateException("D4_LEDGER_MUTEX_UNAVAILABLE");
        }
        BigDecimal current = currentUserBalance(safeUserId, normalizedAsset).orElse(BigDecimal.ZERO);
        BigDecimal balanceAfter = "OUT".equals(normalizedDirection)
                ? current.subtract(safeAmount)
                : current.add(safeAmount);
        mapper.insertLedgerEntry(
                trim(bizNo),
                safeUserId,
                upper(bizType, "ADJUSTMENT"),
                normalizedAsset,
                normalizedDirection,
                safeAmount,
                balanceAfter,
                upper(status, "SUCCESS"),
                trim(remark));
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
