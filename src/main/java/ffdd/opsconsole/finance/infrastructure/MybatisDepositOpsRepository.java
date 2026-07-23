package ffdd.opsconsole.finance.infrastructure;

import ffdd.opsconsole.finance.domain.DepositAggregateView;
import ffdd.opsconsole.finance.domain.DepositBinRiskView;
import ffdd.opsconsole.finance.domain.DepositChargebackView;
import ffdd.opsconsole.finance.domain.DepositFlowView;
import ffdd.opsconsole.finance.domain.DepositOpsRepository;
import ffdd.opsconsole.finance.domain.TopupChargebackRecoveryCommand;
import ffdd.opsconsole.finance.domain.TopupChargebackRecoveryResult;
import ffdd.opsconsole.finance.domain.TopupFeeBufferSnapshot;
import ffdd.opsconsole.finance.domain.TopupWalletSnapshot;
import ffdd.opsconsole.finance.domain.TopupRiskLockSnapshot;
import ffdd.opsconsole.finance.mapper.D1FinanceClosureMapper;
import ffdd.opsconsole.finance.mapper.DepositOrderMapper;
import ffdd.opsconsole.finance.mapper.PaymentRecordMapper;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerRepository;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class MybatisDepositOpsRepository implements DepositOpsRepository {
    private final DepositOrderMapper depositOrderMapper;
    private final PaymentRecordMapper paymentRecordMapper;
    private final D1FinanceClosureMapper financeClosureMapper;
    private final TreasuryLedgerRepository treasuryLedgerRepository;

    @PostConstruct
    void ensureSchema() {
        depositOrderMapper.createReconciliationWriteoffTable();
    }

    @Override
    public List<DepositAggregateView> aggregateToday() {
        return depositOrderMapper.aggregateToday();
    }

    @Override
    public PageResult<DepositFlowView> pageFlows(Collection<String> statuses, Long userId, String keyword, int pageNum, int pageSize) {
        String trimmedKeyword = keyword == null ? null : keyword.trim();
        long total = depositOrderMapper.countFlows(statuses, userId, trimmedKeyword);
        List<DepositFlowView> rows = depositOrderMapper.pageFlows(statuses, userId, trimmedKeyword, pageSize, (pageNum - 1) * pageSize);
        return new PageResult<>(total, pageNum, pageSize, rows);
    }

    @Override
    public boolean hasReconciliationWriteoff(String channelCode, LocalDate reconcileDate) {
        return depositOrderMapper.countReconciliationWriteoff(channelCode, reconcileDate) > 0;
    }

    @Override
    public void writeoffReconciliation(
            String channelCode,
            LocalDate reconcileDate,
            String method,
            String evidenceRef,
            String operator,
            String reason,
            String idempotencyKey) {
        depositOrderMapper.insertReconciliationWriteoff(
                channelCode, reconcileDate, method, evidenceRef, operator, reason, idempotencyKey);
    }

    @Override
    public List<DepositBinRiskView> failedPaymentRiskRows(int threshold) {
        java.util.LinkedHashMap<String, DepositBinRiskView> rows = new java.util.LinkedHashMap<>();
        for (DepositBinRiskView row : paymentRecordMapper.failedPaymentRiskRows(threshold)) {
            rows.put(row.segment(), row);
        }
        for (DepositBinRiskView lock : paymentRecordMapper.activeRiskLocks()) {
            DepositBinRiskView failed = rows.get(lock.segment());
            rows.put(lock.segment(), failed == null
                    ? lock
                    : new DepositBinRiskView(
                            failed.segment(), failed.meta(), failed.fails24h(), true, lock.note(), lock.manual()));
        }
        return List.copyOf(rows.values());
    }

    @Override
    public List<DepositChargebackView> chargebacks() {
        return paymentRecordMapper.chargebacks();
    }

    @Override
    public Optional<DepositChargebackView> findChargeback(String caseNo) {
        return Optional.ofNullable(paymentRecordMapper.findChargeback(caseNo));
    }

    @Override
    public BigDecimal feeBufferBalance() {
        BigDecimal balance = financeClosureMapper.feeBufferBalance();
        return balance == null ? BigDecimal.ZERO : balance;
    }

    @Override
    public long feeEvidenceAnomalyCount() {
        return financeClosureMapper.feeEvidenceAnomalyCount();
    }

    @Override
    public long treasuryReserveAnomalyCount() {
        return financeClosureMapper.treasuryReserveAnomalyCount();
    }

    @Override
    public long historicalBackfillAnomalyCount() {
        return financeClosureMapper.historicalBackfillAnomalyCount();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TopupChargebackRecoveryResult recoverChargeback(TopupChargebackRecoveryCommand command) {
        DepositChargebackView lockedChargeback = paymentRecordMapper.findChargebackForUpdate(command.caseNo());
        if (lockedChargeback == null || !command.userId().equals(lockedChargeback.userId())
                || command.amount().compareTo(lockedChargeback.amount()) != 0
                || command.feeBufferRequired().compareTo(lockedChargeback.feeBufferRequired()) != 0) {
            throw new IllegalStateException("CHARGEBACK_STATE_CHANGED");
        }
        if (!"已入账".equals(lockedChargeback.enteredStatus())) {
            throw new IllegalStateException("CHARGEBACK_LEDGER_ENTRY_NOT_FOUND");
        }
        TopupWalletSnapshot wallet = financeClosureMapper.selectWalletForUpdate(command.userId());
        if (wallet == null) {
            throw new IllegalStateException("CHARGEBACK_WALLET_NOT_FOUND");
        }
        TopupFeeBufferSnapshot feeBuffer = financeClosureMapper.selectFeeBufferForUpdate();
        if (feeBuffer == null) {
            throw new IllegalStateException("TOPUP_FEE_BUFFER_NOT_INITIALIZED");
        }

        BigDecimal available = nonNegative(wallet.usdtAvailable());
        BigDecimal amount = nonNegative(command.amount());
        BigDecimal recovered = available.min(amount).setScale(6, java.math.RoundingMode.HALF_UP);
        BigDecimal walletShortfall = amount.subtract(recovered).max(BigDecimal.ZERO).setScale(6, java.math.RoundingMode.HALF_UP);
        BigDecimal availableAfter = available.subtract(recovered).setScale(6, java.math.RoundingMode.HALF_UP);
        BigDecimal cumulativeBefore = nonNegative(wallet.cumulativeDepositUsdt());
        BigDecimal cumulativeAfter = cumulativeBefore.subtract(amount).max(BigDecimal.ZERO).setScale(6, java.math.RoundingMode.HALF_UP);
        if (financeClosureMapper.updateWallet(command.userId(), availableAfter, cumulativeAfter, wallet.version()) != 1) {
            throw new IllegalStateException("CHARGEBACK_WALLET_CONFLICT");
        }

        String safeCase = command.caseNo().replaceAll("[^A-Za-z0-9_.-]", "-");
        String recoveryBizNo = compactKey("D1-CB-", safeCase, 96);
        String ledgerBizNo = recovered.compareTo(BigDecimal.ZERO) > 0 ? recoveryBizNo : null;
        if (recovered.compareTo(BigDecimal.ZERO) > 0) {
            financeClosureMapper.insertWalletLedger(
                    command.userId(), ledgerBizNo, recovered, availableAfter,
                    "D1拒付追回 · 凭证 " + command.evidenceRef());
        }

        BigDecimal bufferBefore = nonNegative(feeBuffer.balanceUsdt());
        BigDecimal feeRequired = nonNegative(command.feeBufferRequired()).setScale(6, java.math.RoundingMode.HALF_UP);
        BigDecimal feeDeducted = bufferBefore.min(feeRequired).setScale(6, java.math.RoundingMode.HALF_UP);
        BigDecimal feeShortfall = feeRequired.subtract(feeDeducted).max(BigDecimal.ZERO).setScale(6, java.math.RoundingMode.HALF_UP);
        BigDecimal bufferAfter = bufferBefore.subtract(feeDeducted).setScale(6, java.math.RoundingMode.HALF_UP);
        if (financeClosureMapper.updateFeeBuffer(bufferAfter, feeBuffer.version()) != 1) {
            throw new IllegalStateException("TOPUP_FEE_BUFFER_CONFLICT");
        }
        if (feeDeducted.compareTo(BigDecimal.ZERO) > 0) {
            financeClosureMapper.insertFeeBufferLedger(
                    compactKey("FEE-CB-", safeCase, 96), recoveryBizNo, feeDeducted, bufferAfter,
                    command.reason(), command.operator(), command.idempotencyKey());
        }

        boolean anomaly = walletShortfall.compareTo(BigDecimal.ZERO) > 0 || feeShortfall.compareTo(BigDecimal.ZERO) > 0;
        String recoveryStatus = anomaly ? "PARTIAL_ANOMALY" : "RECOVERED";
        String paymentStatus = anomaly ? "CHARGEBACK_PARTIAL" : "CHARGEBACK_RECOVERED";
        if (financeClosureMapper.updateChargebackStatus(command.caseNo(), paymentStatus, command.reason()) != 1) {
            throw new IllegalStateException("CHARGEBACK_STATE_CONFLICT");
        }

        String riskSignalNo = anomaly ? compactKey("RSK-CB-", safeCase, 96) : null;
        if (anomaly) {
            financeClosureMapper.insertRiskSignal(
                    riskSignalNo,
                    command.userId(),
                    "case=" + command.caseNo() + ",walletShortfall=" + walletShortfall.toPlainString()
                            + ",feeBufferShortfall=" + feeShortfall.toPlainString()
                            + ",evidence=" + command.evidenceRef(),
                    command.operator());
        }
        financeClosureMapper.insertRecovery(
                compactKey("REC-CB-", safeCase, 96), command.caseNo(), command.userId(), amount, recovered, walletShortfall,
                feeRequired, feeDeducted, feeShortfall, cumulativeBefore, cumulativeAfter, recoveryStatus,
                command.evidenceRef(), command.reason(), command.operator(), command.idempotencyKey(),
                ledgerBizNo, riskSignalNo);
        financeClosureMapper.resolveLegacyStatusOnlyChargeback(command.caseNo());
        treasuryLedgerRepository.reverseTopupReserve(
                command.caseNo(), amount, "reserve-" + command.idempotencyKey());
        return new TopupChargebackRecoveryResult(
                recovered, walletShortfall, feeDeducted, feeShortfall, recoveryStatus, ledgerBizNo, riskSignalNo);
    }

    @Override
    public void syncAutomaticRiskLocks(int threshold, int lockHours) {
        int safeThreshold = Math.max(1, Math.min(20, threshold));
        int safeHours = Math.max(1, Math.min(72, lockHours));
        financeClosureMapper.upsertAutoBinLocks(safeThreshold, safeHours);
        financeClosureMapper.upsertAutoIpLocks(safeThreshold, safeHours);
        financeClosureMapper.upsertAutoDeviceLocks(safeThreshold, safeHours);
    }

    @Override
    public List<TopupRiskLockSnapshot> activeRiskLockSnapshotsForUpdate() {
        return financeClosureMapper.activeRiskLockSnapshotsForUpdate();
    }

    @Override
    public void setRiskLock(String targetType, String targetValue, boolean locked, int lockHours,
                            String reason, String operator) {
        if (locked) {
            financeClosureMapper.activateManualRiskLock(
                    targetType, targetValue, Math.max(1, Math.min(72, lockHours)), reason, operator);
        } else if (financeClosureMapper.releaseRiskLock(targetType, targetValue, reason, operator) == 0) {
            throw new IllegalStateException("TOPUP_RISK_LOCK_NOT_FOUND");
        }
    }

    private BigDecimal nonNegative(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.max(BigDecimal.ZERO);
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
