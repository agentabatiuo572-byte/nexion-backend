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

    void updateStatus(String withdrawalNo, String status, String failureReason);

    void seedD2FallbackData(Map<String, Long> userIds);
}
