package ffdd.opsconsole.finance.domain;

import ffdd.opsconsole.shared.api.PageResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DepositOpsRepository {
    List<DepositAggregateView> aggregateToday();

    PageResult<DepositFlowView> pageFlows(Collection<String> statuses, Long userId, String keyword, int pageNum, int pageSize);

    boolean hasReconciliationWriteoff(String channelCode, LocalDate reconcileDate);

    void writeoffReconciliation(String channelCode, LocalDate reconcileDate, String method, String evidenceRef,
                                String operator, String reason, String idempotencyKey);

    List<DepositBinRiskView> failedPaymentRiskRows(int threshold);

    List<DepositChargebackView> chargebacks();

    Optional<DepositChargebackView> findChargeback(String caseNo);

    BigDecimal feeBufferBalance();

    default long feeEvidenceAnomalyCount() {
        return 0L;
    }

    default long treasuryReserveAnomalyCount() {
        return 0L;
    }

    default long historicalBackfillAnomalyCount() {
        return 0L;
    }

    TopupChargebackRecoveryResult recoverChargeback(TopupChargebackRecoveryCommand command);

    void syncAutomaticRiskLocks(int threshold, int lockHours);

    List<TopupRiskLockSnapshot> activeRiskLockSnapshotsForUpdate();

    void setRiskLock(String targetType, String targetValue, boolean locked, int lockHours,
                     String reason, String operator);

}
