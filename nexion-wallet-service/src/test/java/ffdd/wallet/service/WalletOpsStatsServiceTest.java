package ffdd.wallet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.wallet.mapper.DepositOrderMapper;
import ffdd.wallet.mapper.ExchangeOrderMapper;
import ffdd.wallet.mapper.WalletLedgerMapper;
import ffdd.wallet.mapper.WithdrawalOrderMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WalletOpsStatsServiceTest {
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-05-26T00:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private final DepositOrderMapper depositOrderMapper = mock(DepositOrderMapper.class);
    private final WithdrawalOrderMapper withdrawalOrderMapper = mock(WithdrawalOrderMapper.class);
    private final ExchangeOrderMapper exchangeOrderMapper = mock(ExchangeOrderMapper.class);
    private final WalletLedgerMapper ledgerMapper = mock(WalletLedgerMapper.class);
    private final WalletOpsStatsService service = new WalletOpsStatsService(
            depositOrderMapper, withdrawalOrderMapper, exchangeOrderMapper, ledgerMapper, CLOCK);

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
}
