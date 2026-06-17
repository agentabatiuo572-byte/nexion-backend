package ffdd.opsconsole.treasury.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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
}
