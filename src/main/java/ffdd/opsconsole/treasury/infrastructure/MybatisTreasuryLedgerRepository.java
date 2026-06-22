package ffdd.opsconsole.treasury.infrastructure;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerBillView;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerRepository;
import ffdd.opsconsole.treasury.mapper.TreasuryLedgerMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

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
    public long countLedgerBills(String type, Long userId, String keyword) {
        return mapper.countLedgerBills(trim(type), userId, trim(keyword));
    }

    @Override
    public List<TreasuryLedgerBillView> pageLedgerBills(String type, Long userId, String keyword, int pageSize, int offset) {
        return mapper.pageLedgerBills(trim(type), userId, trim(keyword), pageSize, offset);
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
    public void createLedgerAdjustment(String adjustmentNo, Long userId, String asset, String direction,
                                       BigDecimal amount, String relatedBizNo, String reason, String operator) {
        mapper.insertLedgerAdjustment(adjustmentNo, userId, trim(asset), trim(direction), amount, trim(relatedBizNo), trim(reason), trim(operator));
    }

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }
}
