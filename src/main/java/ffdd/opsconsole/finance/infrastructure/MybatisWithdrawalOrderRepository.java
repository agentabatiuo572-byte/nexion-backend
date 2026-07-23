package ffdd.opsconsole.finance.infrastructure;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.finance.domain.WithdrawalOrderRepository;
import ffdd.opsconsole.finance.domain.WithdrawalOrderView;
import ffdd.opsconsole.finance.mapper.WithdrawalOrderMapper;
import ffdd.opsconsole.shared.api.PageResult;
import java.math.BigDecimal;
import java.time.LocalDateTime;
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
        return page(status, userId, keyword, minAmount, maxAmount, minRiskScore,
                null, "createdAt", "desc", pageNum, pageSize);
    }

    @Override
    public PageResult<WithdrawalOrderView> page(
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
        String trimmedKeyword = keyword == null ? null : keyword.trim();
        String trimmedIpSegment = ipSegment == null ? null : ipSegment.trim();
        long total = mapper.countPage(
                status, userId, trimmedKeyword, minAmount, maxAmount, minRiskScore, trimmedIpSegment);
        List<WithdrawalOrderView> records = mapper.page(
                status, userId, trimmedKeyword, minAmount, maxAmount, minRiskScore,
                trimmedIpSegment, sortBy, sortDirection, pageSize, (pageNum - 1) * pageSize);
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
    public boolean transitionStatus(String withdrawalNo, String expectedStatus, String newStatus, String failureReason) {
        return mapper.transitionStatus(withdrawalNo, expectedStatus, newStatus, failureReason) == 1;
    }

    @Override
    public boolean transitionStatusWithLifecycle(
            String withdrawalNo,
            String expectedStatus,
            String newStatus,
            String failureReason,
            LocalDateTime holdUntil,
            String owner,
            String period,
            String previousStatus) {
        return mapper.transitionStatusWithLifecycle(
                withdrawalNo, expectedStatus, newStatus, failureReason, holdUntil, owner, period, previousStatus) == 1;
    }

    @Override
    public int releaseExpiredHolds(LocalDateTime now) {
        return mapper.releaseExpiredHolds(now);
    }

    @Override
    public List<String> findExpiredLifecycleNos(LocalDateTime now) {
        return mapper.findExpiredLifecycleNos(now);
    }

    @Override
    public boolean releaseExpiredLifecycle(
            String withdrawalNo, String expectedStatus, String newStatus, String failureReason, LocalDateTime now) {
        return mapper.releaseExpiredLifecycle(withdrawalNo, expectedStatus, newStatus, failureReason, now) == 1;
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
    public int restoreFrozenByUserStatus(Long userId) {
        return mapper.restoreFrozenByUserStatus(userId);
    }

    @Override
    public boolean isFrozenByUserStatus(String withdrawalNo) {
        return mapper.countFrozenByUserStatus(withdrawalNo) > 0;
    }

    @Override
    public long countD2ActionableWithdrawals() {
        return mapper.countD2ActionableWithdrawals();
    }
}
