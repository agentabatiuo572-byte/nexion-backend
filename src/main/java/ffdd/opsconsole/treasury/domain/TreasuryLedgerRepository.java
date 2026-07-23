package ffdd.opsconsole.treasury.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface TreasuryLedgerRepository {
    long countDeposits(LocalDateTime since, String status);

    long countWithdrawals(LocalDateTime since, String status);

    long countExchanges(LocalDateTime since, String status);

    long countLedgers(LocalDateTime since, String direction);

    BigDecimal sumUsdtAvailable();

    BigDecimal sumPendingWithdraw();

    BigDecimal sumNexAvailable();

    default BigDecimal legacyLockOtherLiabilityUsd() {
        return BigDecimal.ZERO;
    }

    BigDecimal sumActiveStakingPrincipalUsdt();

    BigDecimal sumActiveStakingInterestUsdt();

    BigDecimal sumActiveNexLocked();

    BigDecimal sumActiveNexReward();

    BigDecimal sumActiveWithdrawalQueueUsdt();

    default BigDecimal sumWithdrawalRequested24hUsdt() {
        return sumActiveWithdrawalQueueUsdt();
    }

    long countActiveWithdrawalQueue();

    BigDecimal avgActiveWithdrawalQueueRiskScore();

    BigDecimal sumPendingCommissionUsdt();

    BigDecimal sumNetUsdtFlowBetween(LocalDateTime startAt, LocalDateTime endAt);

    List<Map<String, Object>> maturityBuckets(LocalDateTime startAt, LocalDateTime endAt);

    default List<Map<String, Object>> maturityBuckets(
            LocalDateTime startAt, LocalDateTime endAt, int withdrawCooldownDays, String interestMode) {
        return maturityBuckets(startAt, endAt);
    }

    default List<Map<String, Object>> trialStressBuckets(LocalDateTime startAt, LocalDateTime endAt) {
        return List.of();
    }

    List<BigDecimal> riskPressureSeries(LocalDateTime since);

    List<Map<String, Object>> riskRuleBuckets(LocalDateTime since);

    List<Map<String, Object>> riskSeverityBuckets(LocalDateTime since);

    List<Map<String, Object>> riskVolumeBuckets(LocalDateTime since);

    /**
     * B5 read model sourced from K4's already-computed current effective scores.
     * Consumers must not recompute the score dimensions outside K4.
     */
    default Map<String, Object> currentK4RiskScoreSnapshot() {
        return Map.of();
    }

    /** B5 read-only projection of K5's persisted active alerts; newest rows must be returned first. */
    default List<Map<String, Object>> recentK5KycAlerts(LocalDateTime since, int limit) {
        return List.of();
    }

    BigDecimal currentReserveUsd();

    default BigDecimal injectedCumulativeUsd() {
        return BigDecimal.ZERO;
    }

    default BigDecimal genesisDailyLiabilityUsd() {
        return BigDecimal.ZERO;
    }

    default boolean reserveVoucherExists(String voucherNo) {
        return false;
    }

    default BigDecimal walletLedgerReconciliationGapUsdt() {
        return BigDecimal.ZERO;
    }

    Optional<BigDecimal> latestNexUsdtPrice();

    void recordReserveInjection(String voucherNo, BigDecimal amountUsd, String reason, String operator, String idempotencyKey);

    default void recordWithdrawalReserve(String withdrawalNo, BigDecimal amountUsd, String reason, String operator, String idempotencyKey) {
        throw new UnsupportedOperationException("WITHDRAWAL_RESERVE_WRITER_NOT_IMPLEMENTED");
    }

    default void refundWithdrawal(String withdrawalNo, Long userId, BigDecimal amount, String asset, String reason) {
        throw new UnsupportedOperationException("WITHDRAWAL_REFUND_WRITER_NOT_IMPLEMENTED");
    }

    default void refundWithdrawal(
            String withdrawalNo,
            Long userId,
            BigDecimal amount,
            String asset,
            BigDecimal nexBurned,
            String reason) {
        refundWithdrawal(withdrawalNo, userId, amount, asset, reason);
    }

    default void recordTopupReserve(String paymentNo, BigDecimal amountUsd, String eventId) {
        throw new UnsupportedOperationException("TOPUP_RESERVE_WRITER_NOT_IMPLEMENTED");
    }

    default void reverseTopupReserve(String paymentNo, BigDecimal amountUsd, String idempotencyKey) {
        throw new UnsupportedOperationException("TOPUP_RESERVE_REVERSAL_NOT_IMPLEMENTED");
    }

    long countLedgerBills(String type, Long userId, String keyword);

    default long countLedgerBills(String type, Long userId, String keyword, String bizNo) {
        return countLedgerBills(type, userId, bizNo == null ? keyword : bizNo);
    }

    default long countLedgerBills(String type, Long userId, String keyword, String bizNo,
                                  String status, LocalDateTime from, LocalDateTime to) {
        return countLedgerBills(type, userId, keyword, bizNo);
    }

    List<TreasuryLedgerBillView> pageLedgerBills(String type, Long userId, String keyword, int pageSize, int offset);

    default List<TreasuryLedgerBillView> pageLedgerBills(
            String type, Long userId, String keyword, String bizNo, int pageSize, int offset) {
        return pageLedgerBills(type, userId, bizNo == null ? keyword : bizNo, pageSize, offset);
    }

    default List<TreasuryLedgerBillView> pageLedgerBills(
            String type, Long userId, String keyword, String bizNo, String status,
            LocalDateTime from, LocalDateTime to, int pageSize, int offset) {
        return pageLedgerBills(type, userId, keyword, bizNo, pageSize, offset);
    }

    List<TreasuryLedgerBillView> userLedgerRows(Long userId, int limit);

    Optional<BigDecimal> currentUserBalance(Long userId, String asset);

    default Optional<BigDecimal> actualUserBalance(Long userId, String asset) {
        return currentUserBalance(userId, asset);
    }

    void postLedgerEntry(String bizNo, Long userId, String bizType, String asset, String direction,
                         BigDecimal amount, String status, String remark);
}
