package ffdd.opsconsole.treasury.infrastructure;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerBillView;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerRepository;
import ffdd.opsconsole.treasury.mapper.TreasuryLedgerMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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

    @Override
    public void seedD4FallbackData(Map<String, Long> userIds) {
        seedLedger(userIds.get("usr_77D4"), "D4-SEED-DEP-7741", "DEPOSIT", "USDT", "IN",
                "1280.00", "2260.00", "SUCCESS", "D1 fallback topup credited", 12);
        seedLedger(userIds.get("usr_31E8"), "D4-SEED-WD-3188", "WITHDRAWAL", "USDT", "OUT",
                "2600.00", "6720.10", "SUCCESS", "D2 fallback withdrawal completed", 38);
        seedLedger(userIds.get("usr_2231"), "D4-SEED-COM-2231", "TEAM_COMMISSION", "USDT", "IN",
                "180.00", "940.20", "PENDING", "commission cooling balance", 54);
        seedLedger(userIds.get("usr_55B1"), "D4-SEED-ADJ-55B1", "ADJUSTMENT", "USDT", "IN",
                "75.00", "595.00", "SUCCESS", "manual finance correction", 76);
        seedLedger(userIds.get("usr_31E8"), "D4-SEED-NEX-3188", "EARNING", "NEX", "IN",
                "420.00", "420420.00", "SUCCESS", "NEX earning settlement", 95);
        seedLedger(userIds.get("usr_8807"), "D4-SEED-CB-8807", "CHARGEBACK", "USDT", "OUT",
                "188.00", "3892.50", "SUCCESS", "card chargeback reversal", 118);
    }

    private void seedLedger(Long userId, String bizNo, String bizType, String asset, String direction,
                            String amount, String balanceAfter, String status, String remark, int minutesAgo) {
        if (userId == null) {
            return;
        }
        mapper.insertD4SeedLedger(
                userId,
                bizNo,
                bizType,
                asset,
                direction,
                new BigDecimal(amount),
                new BigDecimal(balanceAfter),
                status,
                remark,
                minutesAgo);
    }

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }
}
