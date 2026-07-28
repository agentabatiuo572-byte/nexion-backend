package ffdd.opsconsole.treasury.infrastructure;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ffdd.opsconsole.treasury.mapper.TreasuryLedgerMapper;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

class MybatisTreasuryLedgerRepositoryTest {

    @Test
    void withdrawalComponentsKeepRunningBalanceContinuousAndEachFirstInsertPublishesOutbox() {
        TreasuryLedgerMapper mapper = mock(TreasuryLedgerMapper.class);
        EventOutboxService outbox = mock(EventOutboxService.class);
        MybatisTreasuryLedgerRepository repository = new MybatisTreasuryLedgerRepository(mapper, outbox);
        for (String bizNo : java.util.List.of(
                "WD-1:USDT:PRINCIPAL", "WD-1:USDT:NETWORK_FEE", "WD-1:USDT:PENALTY_FEE")) {
            when(mapper.lockLedgerMutex(uniqueKey(bizNo, "USDT", "OUT")))
                    .thenReturn(uniqueKey(bizNo, "USDT", "OUT"));
        }
        when(mapper.lockLedgerMutex("D4_LEDGER_7_USDT")).thenReturn("D4_LEDGER_7_USDT");
        when(mapper.currentUserBalance(7L, "USDT"))
                .thenReturn(new BigDecimal("100.000000"),
                        new BigDecimal("22.000000"), new BigDecimal("20.000000"));
        when(mapper.insertLedgerEntry(
                "WD-1:USDT:PRINCIPAL", 7L, "WITHDRAW_NET_PRINCIPAL", "USDT", "OUT",
                new BigDecimal("78.000000"), new BigDecimal("22.000000"), "POSTED",
                "D2 withdrawal net principal")).thenReturn(1);
        when(mapper.insertLedgerEntry(
                "WD-1:USDT:NETWORK_FEE", 7L, "WITHDRAW_NETWORK_FEE", "USDT", "OUT",
                new BigDecimal("2.000000"), new BigDecimal("20.000000"), "POSTED",
                "D5 actual network fee after NEX offset")).thenReturn(1);
        when(mapper.insertLedgerEntry(
                "WD-1:USDT:PENALTY_FEE", 7L, "WITHDRAW_PENALTY_FEE", "USDT", "OUT",
                new BigDecimal("20.000000"), BigDecimal.ZERO.setScale(6), "POSTED",
                "H1 actual withdrawal penalty after NEX offset")).thenReturn(1);

        repository.postLedgerEntry(
                "WD-1:USDT:PRINCIPAL", 7L, "WITHDRAW_NET_PRINCIPAL", "USDT", "OUT",
                new BigDecimal("78.000000"), "POSTED", "D2 withdrawal net principal");
        repository.postLedgerEntry(
                "WD-1:USDT:NETWORK_FEE", 7L, "WITHDRAW_NETWORK_FEE", "USDT", "OUT",
                new BigDecimal("2.000000"), "POSTED", "D5 actual network fee after NEX offset");
        repository.postLedgerEntry(
                "WD-1:USDT:PENALTY_FEE", 7L, "WITHDRAW_PENALTY_FEE", "USDT", "OUT",
                new BigDecimal("20.000000"), "POSTED", "H1 actual withdrawal penalty after NEX offset");

        verify(outbox).publish("WALLET_LEDGER", "WD-1:USDT:PRINCIPAL", "wallet.ledger_posted", Map.of(
                "bizNo", "WD-1:USDT:PRINCIPAL", "userId", 7L,
                "bizType", "WITHDRAW_NET_PRINCIPAL", "asset", "USDT", "direction", "OUT",
                "amount", new BigDecimal("78.000000"), "balanceAfter", new BigDecimal("22.000000"),
                "status", "POSTED"));
        verify(outbox).publish("WALLET_LEDGER", "WD-1:USDT:NETWORK_FEE", "wallet.ledger_posted", Map.of(
                "bizNo", "WD-1:USDT:NETWORK_FEE", "userId", 7L,
                "bizType", "WITHDRAW_NETWORK_FEE", "asset", "USDT", "direction", "OUT",
                "amount", new BigDecimal("2.000000"), "balanceAfter", new BigDecimal("20.000000"),
                "status", "POSTED"));
        verify(outbox).publish("WALLET_LEDGER", "WD-1:USDT:PENALTY_FEE", "wallet.ledger_posted", Map.of(
                "bizNo", "WD-1:USDT:PENALTY_FEE", "userId", 7L,
                "bizType", "WITHDRAW_PENALTY_FEE", "asset", "USDT", "direction", "OUT",
                "amount", new BigDecimal("20.000000"), "balanceAfter", BigDecimal.ZERO.setScale(6),
                "status", "POSTED"));
    }

    @Test
    void withdrawalRefundRestoresReservedUsdtAndBurnedNexWithExactPostBalances() {
        TreasuryLedgerMapper mapper = mock(TreasuryLedgerMapper.class);
        MybatisTreasuryLedgerRepository repository = new MybatisTreasuryLedgerRepository(
                mapper, mock(EventOutboxService.class));
        when(mapper.actualUserBalance(7L, "USDT")).thenReturn(new BigDecimal("20.000000"));
        when(mapper.actualUserBalance(7L, "NEX")).thenReturn(new BigDecimal("5.000000"));
        when(mapper.releasePendingWithdrawalWithNex(
                7L, new BigDecimal("100.000000"), new BigDecimal("50.000000"))).thenReturn(1);
        when(mapper.insertLedgerEntry(
                "D2-REFUND-WD-1", 7L, "WITHDRAW_REFUND", "USDT", "IN",
                new BigDecimal("100.000000"), new BigDecimal("120.000000"), "SUCCESS", "rejected"))
                .thenReturn(1);
        when(mapper.insertLedgerEntry(
                "D2-NEX-REFUND-WD-1", 7L, "WITHDRAW_FEE_OFFSET_REFUND", "NEX", "IN",
                new BigDecimal("50.000000"), new BigDecimal("55.000000"), "SUCCESS", "rejected"))
                .thenReturn(1);

        repository.refundWithdrawal(
                "WD-1", 7L, new BigDecimal("100"), "USDT", new BigDecimal("50"), "rejected");

        verify(mapper).releasePendingWithdrawalWithNex(
                7L, new BigDecimal("100.000000"), new BigDecimal("50.000000"));
        verify(mapper).insertLedgerEntry(
                "D2-REFUND-WD-1", 7L, "WITHDRAW_REFUND", "USDT", "IN",
                new BigDecimal("100.000000"), new BigDecimal("120.000000"), "SUCCESS", "rejected");
        verify(mapper).insertLedgerEntry(
                "D2-NEX-REFUND-WD-1", 7L, "WITHDRAW_FEE_OFFSET_REFUND", "NEX", "IN",
                new BigDecimal("50.000000"), new BigDecimal("55.000000"), "SUCCESS", "rejected");
    }

    @Test
    void immutableLedgerRejectsSameBusinessKeyWithDifferentFingerprint() {
        TreasuryLedgerMapper mapper = mock(TreasuryLedgerMapper.class);
        MybatisTreasuryLedgerRepository repository = new MybatisTreasuryLedgerRepository(
                mapper, mock(EventOutboxService.class));
        WalletLedgerEntity existing = new WalletLedgerEntity();
        existing.setUserId(7L);
        existing.setBizType("ADJUSTMENT");
        existing.setAsset("USDT");
        existing.setDirection("IN");
        existing.setAmount(new BigDecimal("10"));
        existing.setBalanceAfter(new BigDecimal("20"));
        existing.setStatus("SUCCESS");
        existing.setRemark("first");
        when(mapper.findLedgerEntry("BIZ-1", "USDT", "IN")).thenReturn(existing);
        when(mapper.lockLedgerMutex(uniqueKey("BIZ-1", "USDT", "IN")))
                .thenReturn(uniqueKey("BIZ-1", "USDT", "IN"));
        when(mapper.lockLedgerMutex("D4_LEDGER_7_USDT")).thenReturn("D4_LEDGER_7_USDT");
        when(mapper.currentUserBalance(7L, "USDT")).thenReturn(new BigDecimal("10"));

        assertThatThrownBy(() -> repository.postLedgerEntry(
                "BIZ-1", 7L, "ADJUSTMENT", "USDT", "IN",
                new BigDecimal("11"), "SUCCESS", "changed"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("D4_LEDGER_IDEMPOTENCY_CONFLICT");
    }

    @Test
    void exactReplayAfterLaterEntriesUsesStoredFingerprintAndDoesNotRecomputeBalance() {
        TreasuryLedgerMapper mapper = mock(TreasuryLedgerMapper.class);
        EventOutboxService outbox = mock(EventOutboxService.class);
        MybatisTreasuryLedgerRepository repository = new MybatisTreasuryLedgerRepository(mapper, outbox);
        WalletLedgerEntity existing = new WalletLedgerEntity();
        existing.setUserId(7L);
        existing.setBizType("ADJUSTMENT");
        existing.setAsset("USDT");
        existing.setDirection("IN");
        existing.setAmount(new BigDecimal("10"));
        existing.setBalanceAfter(new BigDecimal("20"));
        existing.setStatus("SUCCESS");
        existing.setRemark("same");
        String uniqueKey = uniqueKey("BIZ-REPLAY", "USDT", "IN");
        when(mapper.lockLedgerMutex(uniqueKey)).thenReturn(uniqueKey);
        when(mapper.lockLedgerMutex("D4_LEDGER_7_USDT")).thenReturn("D4_LEDGER_7_USDT");
        when(mapper.findLedgerEntry("BIZ-REPLAY", "USDT", "IN")).thenReturn(existing);
        when(mapper.currentUserBalance(7L, "USDT")).thenReturn(new BigDecimal("999"));

        repository.postLedgerEntry(
                "BIZ-REPLAY", 7L, "ADJUSTMENT", "USDT", "IN",
                new BigDecimal("10"), "SUCCESS", "same");

        verify(mapper, never()).currentUserBalance(7L, "USDT");
        verify(mapper, never()).insertLedgerEntry(
                "BIZ-REPLAY", 7L, "ADJUSTMENT", "USDT", "IN",
                new BigDecimal("10"), new BigDecimal("1009"), "SUCCESS", "same");
        verify(outbox, never()).publish(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void duplicateInsertFromAnotherUserIsRecheckedAndNeverPublishesFalseOutbox() {
        TreasuryLedgerMapper mapper = mock(TreasuryLedgerMapper.class);
        EventOutboxService outbox = mock(EventOutboxService.class);
        MybatisTreasuryLedgerRepository repository = new MybatisTreasuryLedgerRepository(mapper, outbox);
        WalletLedgerEntity winner = new WalletLedgerEntity();
        winner.setUserId(7L);
        winner.setBizType("ADJUSTMENT");
        winner.setAsset("USDT");
        winner.setDirection("IN");
        winner.setAmount(new BigDecimal("10"));
        winner.setBalanceAfter(new BigDecimal("20"));
        winner.setStatus("SUCCESS");
        winner.setRemark("winner");
        String uniqueKey = uniqueKey("BIZ-RACE", "USDT", "IN");
        when(mapper.lockLedgerMutex(uniqueKey)).thenReturn(uniqueKey);
        when(mapper.lockLedgerMutex("D4_LEDGER_8_USDT")).thenReturn("D4_LEDGER_8_USDT");
        when(mapper.findLedgerEntry("BIZ-RACE", "USDT", "IN")).thenReturn(null, winner);
        when(mapper.currentUserBalance(8L, "USDT")).thenReturn(new BigDecimal("5"));
        when(mapper.insertLedgerEntry(
                "BIZ-RACE", 8L, "ADJUSTMENT", "USDT", "IN",
                new BigDecimal("10"), new BigDecimal("15"), "SUCCESS", "loser"))
                .thenThrow(new DuplicateKeyException("uk_wallet_ledger_biz"));

        assertThatThrownBy(() -> repository.postLedgerEntry(
                "BIZ-RACE", 8L, "ADJUSTMENT", "USDT", "IN",
                new BigDecimal("10"), "SUCCESS", "loser"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("D4_LEDGER_IDEMPOTENCY_CONFLICT");

        verify(outbox, never()).publish(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyMap());
    }

    private static String uniqueKey(String bizNo, String asset, String direction) {
        return "D4_BIZ_" + UUID.nameUUIDFromBytes(
                (bizNo + "|" + asset + "|" + direction).getBytes(StandardCharsets.UTF_8));
    }
}
