package ffdd.opsconsole.finance.infrastructure;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.finance.domain.WithdrawalOrderRepository;
import ffdd.opsconsole.finance.domain.WithdrawalOrderView;
import ffdd.opsconsole.finance.mapper.WithdrawalOrderMapper;
import ffdd.opsconsole.shared.api.PageResult;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MybatisWithdrawalOrderRepository implements WithdrawalOrderRepository {
    private final WithdrawalOrderMapper mapper;

    @Override
    public PageResult<WithdrawalOrderView> page(
            String status,
            Long userId,
            String keyword,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            Integer minRiskScore,
            int pageNum,
            int pageSize) {
        String trimmedKeyword = keyword == null ? null : keyword.trim();
        long total = mapper.countPage(status, userId, trimmedKeyword, minAmount, maxAmount, minRiskScore);
        List<WithdrawalOrderView> records = mapper.page(status, userId, trimmedKeyword, minAmount, maxAmount, minRiskScore, pageSize, (pageNum - 1) * pageSize);
        return new PageResult<>(total, pageNum, pageSize, records);
    }

    @Override
    public Optional<WithdrawalOrderView> findByWithdrawalNo(String withdrawalNo) {
        return Optional.ofNullable(mapper.findByWithdrawalNo(withdrawalNo));
    }

    @Override
    public Optional<String> findUserCountryCode(Long userId) {
        return Optional.ofNullable(mapper.findUserCountryCode(userId));
    }

    @Override
    public void updateStatus(String withdrawalNo, String status, String failureReason) {
        mapper.updateStatus(withdrawalNo, status, failureReason);
    }

    @Override
    public boolean transitionK5FrozenStatus(
            String withdrawalNo, String ticketId, String status, String failureReason) {
        return mapper.transitionK5FrozenStatus(withdrawalNo, ticketId, status, failureReason) > 0;
    }

    @Override
    public boolean freezeForK5Review(String withdrawalNo, String expectedStatus, String ticketId) {
        return mapper.freezeForK5Review(withdrawalNo, expectedStatus, ticketId) > 0;
    }

    @Override
    public int freezePendingByUserId(Long userId, String reason) {
        return mapper.freezePendingByUserId(userId, reason);
    }

    @Override
    public long countD2ActionableWithdrawals() {
        return mapper.countD2ActionableWithdrawals();
    }
}
