package ffdd.opsconsole.finance.domain;

import ffdd.opsconsole.shared.api.PageResult;
import java.math.BigDecimal;
import java.util.Map;
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

    int freezePendingByUserId(Long userId, String reason);

    long countD2SeedWithdrawals();

    long countD2ActionableWithdrawals();

    void seedD2FallbackData(Map<String, Long> userIds);
}
