package ffdd.opsconsole.treasury.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TreasuryLedgerRepository {
    long countDeposits(LocalDateTime since, String status);

    long countWithdrawals(LocalDateTime since, String status);

    long countExchanges(LocalDateTime since, String status);

    long countLedgers(LocalDateTime since, String direction);

    BigDecimal sumUsdtAvailable();

    BigDecimal sumPendingWithdraw();

    BigDecimal sumNexAvailable();

    BigDecimal sumActiveStakingPrincipalUsdt();

    BigDecimal sumActiveStakingInterestUsdt();

    BigDecimal sumActiveNexLocked();

    BigDecimal sumActiveNexReward();

    BigDecimal sumActiveWithdrawalQueueUsdt();

    long countActiveWithdrawalQueue();

    BigDecimal avgActiveWithdrawalQueueRiskScore();

    BigDecimal sumPendingCommissionUsdt();

    BigDecimal sumNetUsdtFlowBetween(LocalDateTime startAt, LocalDateTime endAt);

    long countLedgerBills(String type, Long userId, String keyword);

    List<TreasuryLedgerBillView> pageLedgerBills(String type, Long userId, String keyword, int pageSize, int offset);

    List<TreasuryLedgerBillView> userLedgerRows(Long userId, int limit);

    Optional<BigDecimal> currentUserBalance(Long userId, String asset);

    void createLedgerAdjustment(String adjustmentNo, Long userId, String asset, String direction,
                                BigDecimal amount, String relatedBizNo, String reason, String operator);
}
