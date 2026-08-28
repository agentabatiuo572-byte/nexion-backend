package ffdd.opsconsole.finance.domain;

import ffdd.opsconsole.shared.api.PageResult;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface WithdrawalOrderRepository {
    PageResult<WithdrawalOrderView> page(
            String status,
            Long userId,
            String keyword,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            Integer minRiskScore,
            int pageNum,
            int pageSize);

    default PageResult<WithdrawalOrderView> page(
            String status,
            Long userId,
            String keyword,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            Integer minRiskScore,
            String ipSegment,
            String sortBy,
            String sortDirection,
            int pageNum,
            int pageSize) {
        return page(status, userId, keyword, minAmount, maxAmount, minRiskScore, pageNum, pageSize);
    }

    Optional<WithdrawalOrderView> findByWithdrawalNo(String withdrawalNo);

    Optional<String> findUserCountryCode(Long userId);

    void updateStatus(String withdrawalNo, String status, String failureReason);

    boolean transitionStatus(String withdrawalNo, String expectedStatus, String newStatus, String failureReason);

    default boolean confirmSent(
            String withdrawalNo,
            String expectedStatus,
            String chainTxHash,
            LocalDateTime completedAt) {
        return false;
    }

    default boolean chainTxHashUsedByOtherWithdrawal(String withdrawalNo, String chainTxHash) {
        return false;
    }

    default boolean transitionStatusWithLifecycle(
            String withdrawalNo,
            String expectedStatus,
            String newStatus,
            String failureReason,
            LocalDateTime holdUntil,
            String owner,
            String period,
            String previousStatus) {
        return transitionStatus(withdrawalNo, expectedStatus, newStatus, failureReason);
    }

    default int releaseExpiredHolds(LocalDateTime now) {
        return 0;
    }

    default List<String> findExpiredLifecycleNos(LocalDateTime now) {
        return List.of();
    }

    default boolean releaseExpiredLifecycle(
            String withdrawalNo, String expectedStatus, String newStatus, String failureReason, LocalDateTime now) {
        return false;
    }

    default boolean accelerateDevelopmentH1Hold(
            String withdrawalNo,
            String expectedStatus,
            LocalDateTime expectedHoldUntil,
            LocalDateTime simulatedDueAt) {
        return false;
    }

    /**
     * Serializes development-only H1 expiry simulations for one withdrawal.
     * The concrete MySQL implementation holds the row mutex for the surrounding
     * idempotent command transaction and returns false outside the exact dev scope.
     */
    default boolean lockDevelopmentH1Hold(String withdrawalNo) {
        return false;
    }

    int freezePendingByUserId(Long userId, String reason);

    int restoreFrozenByUserStatus(Long userId);

    boolean isFrozenByUserStatus(String withdrawalNo);

    long countD2ActionableWithdrawals();
}
