package ffdd.wallet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.wallet.mapper.DepositOrderMapper;
import ffdd.wallet.mapper.ExchangeOrderMapper;
import ffdd.wallet.mapper.NexLockOrderMapper;
import ffdd.wallet.mapper.StakingPositionMapper;
import ffdd.wallet.mapper.UserWalletMapper;
import ffdd.wallet.mapper.WalletLedgerMapper;
import ffdd.wallet.mapper.WithdrawalOrderMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WalletOpsStatsServiceTest {
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-05-26T00:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private final DepositOrderMapper depositOrderMapper = mock(DepositOrderMapper.class);
    private final WithdrawalOrderMapper withdrawalOrderMapper = mock(WithdrawalOrderMapper.class);
    private final ExchangeOrderMapper exchangeOrderMapper = mock(ExchangeOrderMapper.class);
    private final UserWalletMapper userWalletMapper = mock(UserWalletMapper.class);
    private final StakingPositionMapper stakingPositionMapper = mock(StakingPositionMapper.class);
    private final NexLockOrderMapper nexLockOrderMapper = mock(NexLockOrderMapper.class);
    private final WalletLedgerMapper ledgerMapper = mock(WalletLedgerMapper.class);
    private final WalletOpsStatsService service = new WalletOpsStatsService(
            depositOrderMapper,
            withdrawalOrderMapper,
            exchangeOrderMapper,
            userWalletMapper,
            stakingPositionMapper,
            nexLockOrderMapper,
            ledgerMapper,
            CLOCK,
            BigDecimal.valueOf(5000),
            BigDecimal.valueOf(0.17),
            BigDecimal.valueOf(85),
            BigDecimal.valueOf(100));

    @Test
    @SuppressWarnings("unchecked")
    void summarizesWalletAssetKpisWithDefaultWindow() {
        when(depositOrderMapper.selectCount(any()))
                .thenReturn(11L)
                .thenReturn(8L)
                .thenReturn(2L)
                .thenReturn(1L);
        when(withdrawalOrderMapper.selectCount(any()))
                .thenReturn(9L)
                .thenReturn(3L)
                .thenReturn(2L)
                .thenReturn(1L)
                .thenReturn(2L)
                .thenReturn(1L);
        when(exchangeOrderMapper.selectCount(any()))
                .thenReturn(6L)
                .thenReturn(4L)
                .thenReturn(1L)
                .thenReturn(1L);
        when(ledgerMapper.selectCount(any()))
                .thenReturn(20L)
                .thenReturn(12L)
                .thenReturn(8L);

        Map<String, Object> stats = service.summary(0);

        assertThat(stats)
                .containsEntry("service", "nexion-wallet-service")
                .containsEntry("days", 7);
        assertThat((Map<String, Object>) stats.get("deposits"))
                .containsEntry("total", 11L)
                .containsEntry("success", 8L)
                .containsEntry("pending", 2L)
                .containsEntry("dead", 1L);
        assertThat((Map<String, Object>) stats.get("withdrawals"))
                .containsEntry("total", 9L)
                .containsEntry("pendingChain", 3L)
                .containsEntry("chainSubmitted", 2L)
                .containsEntry("success", 1L)
                .containsEntry("failed", 2L)
                .containsEntry("dead", 1L);
        assertThat((Map<String, Object>) stats.get("exchanges"))
                .containsEntry("total", 6L)
                .containsEntry("completed", 4L)
                .containsEntry("reviewing", 1L)
                .containsEntry("rejected", 1L);
        assertThat((Map<String, Object>) stats.get("ledger"))
                .containsEntry("total", 20L)
                .containsEntry("credits", 12L)
                .containsEntry("debits", 8L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void dualLedgerAggregatesServerCanonicalBalancesAndRisk() {
        when(userWalletMapper.sumUsdtAvailable()).thenReturn(BigDecimal.valueOf(1000));
        when(userWalletMapper.sumPendingWithdraw()).thenReturn(BigDecimal.valueOf(150));
        when(userWalletMapper.sumNexAvailable()).thenReturn(BigDecimal.valueOf(2000));
        when(stakingPositionMapper.sumActivePrincipalUsdt()).thenReturn(BigDecimal.valueOf(500));
        when(stakingPositionMapper.sumActiveInterestUsdt()).thenReturn(BigDecimal.valueOf(50));
        when(nexLockOrderMapper.sumActiveAmountNex()).thenReturn(BigDecimal.valueOf(1000));
        when(nexLockOrderMapper.sumActiveRewardNex()).thenReturn(BigDecimal.valueOf(100));
        when(withdrawalOrderMapper.sumActiveQueueUsdt()).thenReturn(BigDecimal.valueOf(300));
        when(withdrawalOrderMapper.countActiveQueue()).thenReturn(3L);
        when(withdrawalOrderMapper.avgActiveQueueRiskScore()).thenReturn(BigDecimal.valueOf(42.4));
        when(ledgerMapper.sumPendingCommissionUsdt()).thenReturn(BigDecimal.valueOf(80));
        when(ledgerMapper.sumNetUsdtFlowBetween(any(), any())).thenReturn(BigDecimal.valueOf(-20));

        Map<String, Object> overview = service.dualLedger();

        Map<String, Object> snapshot = (Map<String, Object>) overview.get("snapshot");
        assertThat(snapshot)
                .containsEntry("reserveUsd", BigDecimal.valueOf(5000).setScale(2))
                .containsEntry("liabilitiesUsd", BigDecimal.valueOf(2607).setScale(2))
                .containsEntry("queueBacklogCount", 3L)
                .containsEntry("avgRiskScore", 42L);
        assertThat((List<BigDecimal>) snapshot.get("coverageSeries")).hasSize(8);

        List<Map<String, Object>> accounts = (List<Map<String, Object>>) overview.get("accounts");
        assertThat(accounts).hasSize(8);
        assertThat(accounts.get(0))
                .containsEntry("key", "balance")
                .containsEntry("amount", BigDecimal.valueOf(1000).setScale(2));
        assertThat((List<String>) overview.get("sources")).contains("nx_user_wallet", "nexion.wallet.dual-ledger.* config");
    }
}
