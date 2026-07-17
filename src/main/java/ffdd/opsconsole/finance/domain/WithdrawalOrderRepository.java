package ffdd.opsconsole.finance.domain;

import ffdd.opsconsole.shared.api.PageResult;
import java.math.BigDecimal;
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

    Optional<WithdrawalOrderView> findByWithdrawalNo(String withdrawalNo);

    Optional<String> findUserCountryCode(Long userId);

    void updateStatus(String withdrawalNo, String status, String failureReason);

    boolean transitionK5FrozenStatus(String withdrawalNo, String ticketId, String status, String failureReason);

    boolean freezeForK5Review(String withdrawalNo, String expectedStatus, String ticketId);

    int freezePendingByUserId(Long userId, String reason);

    long countD2ActionableWithdrawals();
}
