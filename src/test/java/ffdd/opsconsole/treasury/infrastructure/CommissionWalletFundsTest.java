package ffdd.opsconsole.treasury.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.treasury.mapper.TreasuryLedgerMapper;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.annotation.Transactional;

class CommissionWalletFundsTest {
    private static final Long USER = 42L;
    private static final Long EVENT = 71L;
    private static final BigDecimal AMOUNT = new BigDecimal("2.500000");
    private final TreasuryLedgerMapper mapper = mock(TreasuryLedgerMapper.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final MybatisTreasuryLedgerRepository repository = new MybatisTreasuryLedgerRepository(mapper, outbox);
    private final Map<String, WalletLedgerEntity> ledger = new HashMap<>();
    private BigDecimal wallet = new BigDecimal("10.000000");
    private String status = "UNLOCKED";
    private boolean pendingAccrual = true;

    private void funds(String asset) {
        when(mapper.lockCommissionFundsEvent(EVENT)).thenAnswer(call ->
                new TreasuryLedgerMapper.CommissionFundsRow(USER, asset, AMOUNT, status));
        when(mapper.findLedgerEntry(anyString(), eq(asset), anyString()))
                .thenAnswer(call -> ledger.get(call.getArgument(0) + ":" + call.getArgument(2)));
        when(mapper.lockCommissionWallet(USER, asset)).thenAnswer(call -> wallet);
        when(mapper.closeCommissionAccrual(EVENT, USER, asset)).thenAnswer(call -> {
            pendingAccrual = false;
            return 1;
        });
        when(mapper.adjustCommissionWallet(eq(USER), eq(asset), any())).thenAnswer(call -> {
            wallet = wallet.add(call.getArgument(2));
            return 1;
        });
        when(mapper.insertLedgerEntry(anyString(), eq(USER), eq("TEAM_COMMISSION"), eq(asset),
                anyString(), any(), any(), eq("SUCCESS"), anyString())).thenAnswer(call -> {
                    WalletLedgerEntity row = new WalletLedgerEntity();
                    row.setUserId(USER);
                    row.setBizType("TEAM_COMMISSION");
                    row.setAsset(asset);
                    row.setDirection(call.getArgument(4));
                    row.setAmount(call.getArgument(5));
                    row.setBalanceAfter(call.getArgument(6));
                    row.setStatus("SUCCESS");
                    row.setRemark(call.getArgument(8));
                    ledger.put(call.getArgument(0) + ":" + call.getArgument(4), row);
                    return 1;
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"USDT", "NEX"})
    void releaseAndReversalMoveTheActualWalletOnceAndRecordExactBalances(String asset) {
        funds(asset);
        repository.releaseCommissionFunds(EVENT);
        repository.releaseCommissionFunds(EVENT);
        assertThat(wallet).isEqualByComparingTo("12.5");
        assertThat(pendingAccrual).isFalse();
        assertThat(ledger.get("F5-COMMISSION-71-RELEASE:IN").getBalanceAfter()).isEqualByComparingTo(wallet);
        verify(mapper, times(1)).adjustCommissionWallet(USER, asset, AMOUNT);
        status = "REVERSED";
        assertThat(repository.reverseCommissionFunds(EVENT)).isTrue();
        assertThat(repository.reverseCommissionFunds(EVENT)).isTrue();
        assertThat(wallet).isEqualByComparingTo("10");
        assertThat(ledger.get("F5-COMMISSION-71-REVERSE:OUT").getBalanceAfter()).isEqualByComparingTo(wallet);
        verify(mapper, times(1)).adjustCommissionWallet(USER, asset, AMOUNT.negate());
        verify(outbox, times(2)).publish(eq("WALLET_LEDGER"), anyString(), eq("wallet.ledger_posted"), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"COOLING", "PENDING", "FROZEN", "PAID", "SETTLED"})
    void anUnreleasedStateCannotCreditTheWallet(String state) {
        funds("USDT");
        status = state;
        assertThatThrownBy(() -> repository.releaseCommissionFunds(EVENT))
                .hasMessage("COMMISSION_FUNDS_STATE_CONFLICT");
        assertThat(wallet).isEqualByComparingTo("10");
        verify(mapper, never()).adjustCommissionWallet(any(), anyString(), any());
    }

    @Test
    void reversingAnUnreleasedAccrualCannotTakeExistingWalletFunds() {
        funds("USDT");
        status = "REJECTED";
        assertThat(repository.reverseCommissionFunds(EVENT)).isFalse();
        assertThat(wallet).isEqualByComparingTo("10");
        assertThat(ledger).isEmpty();
        assertThat(pendingAccrual).isFalse();
        verify(mapper, never()).lockCommissionWallet(any(), anyString());
    }

    @Test
    void fundsSpentAfterReleaseMakeReversalFailWithoutAnotherDebit() {
        funds("USDT");
        repository.releaseCommissionFunds(EVENT);
        wallet = BigDecimal.ONE;
        status = "REVERSED";
        assertThatThrownBy(() -> repository.reverseCommissionFunds(EVENT))
                .hasMessage("COMMISSION_WALLET_INSUFFICIENT_BALANCE");
        assertThat(wallet).isEqualByComparingTo("1");
        assertThat(ledger).hasSize(1);
        verify(mapper, never()).adjustCommissionWallet(USER, "USDT", AMOUNT.negate());
    }

    @Test
    void legacySuccessLedgerWithoutAWalletReleaseReceiptCannotAuthorizeADebit() {
        funds("USDT");
        WalletLedgerEntity legacy = new WalletLedgerEntity();
        legacy.setUserId(USER);
        legacy.setBizType("TEAM_COMMISSION");
        legacy.setAsset("USDT");
        legacy.setDirection("IN");
        legacy.setAmount(AMOUNT);
        legacy.setStatus("SUCCESS");
        legacy.setBalanceAfter(new BigDecimal("9999"));
        ledger.put("F1-VRANKREWARD-71:IN", legacy);
        status = "REVERSED";
        assertThat(repository.reverseCommissionFunds(EVENT)).isFalse();
        assertThat(wallet).isEqualByComparingTo("10");
        assertThat(ledger).hasSize(1);
        verify(mapper, never()).adjustCommissionWallet(any(), anyString(), any());
    }

    @Test
    void walletWriteFailureCannotProduceASuccessfulLedger() {
        funds("USDT");
        when(mapper.adjustCommissionWallet(USER, "USDT", AMOUNT)).thenReturn(0);
        assertThatThrownBy(() -> repository.releaseCommissionFunds(EVENT))
                .hasMessage("COMMISSION_WALLET_WRITE_CONFLICT");
        assertThat(ledger).isEmpty();
        verify(outbox, never()).publish(anyString(), anyString(), anyString(), any());
    }

    @Test
    void outboxFailurePropagatesToTheTransactionOwner() {
        funds("NEX");
        when(outbox.publish(eq("WALLET_LEDGER"), anyString(), eq("wallet.ledger_posted"), any()))
                .thenThrow(new IllegalStateException("outbox unavailable"));
        assertThatThrownBy(() -> repository.releaseCommissionFunds(EVENT)).hasMessage("outbox unavailable");
    }

    @Test
    void changedEventAmountCannotReuseTheReleaseReceipt() {
        funds("USDT");
        repository.releaseCommissionFunds(EVENT);
        when(mapper.lockCommissionFundsEvent(EVENT)).thenReturn(
                new TreasuryLedgerMapper.CommissionFundsRow(USER, "USDT", BigDecimal.TEN, "UNLOCKED"));
        assertThatThrownBy(() -> repository.releaseCommissionFunds(EVENT))
                .hasMessage("D4_LEDGER_IDEMPOTENCY_CONFLICT");
        assertThat(wallet).isEqualByComparingTo("12.5");
    }

    @Test
    void pendingCommissionLedgerUsesActualBalanceWithoutPretendingToCreditIt() {
        when(mapper.lockLedgerMutex(anyString())).thenAnswer(call -> call.getArgument(0));
        when(mapper.actualUserBalance(USER, "USDT")).thenReturn(wallet);
        when(mapper.insertLedgerEntry("F2-NETWORK-71", USER, "TEAM_COMMISSION", "USDT", "IN",
                AMOUNT, wallet, "PENDING", "cooling accrual")).thenReturn(1);
        repository.postLedgerEntry("F2-NETWORK-71", USER, "TEAM_COMMISSION", "USDT", "IN",
                AMOUNT, "PENDING", "cooling accrual");
        verify(mapper).insertLedgerEntry("F2-NETWORK-71", USER, "TEAM_COMMISSION", "USDT", "IN",
                AMOUNT, wallet, "PENDING", "cooling accrual");
        verify(mapper, never()).currentUserBalance(USER, "USDT");
        verify(mapper, never()).adjustCommissionWallet(any(), anyString(), any());
    }

    @Test
    void databaseContractLocksEventAndWalletAndGuardsNonnegativeBalances() throws Exception {
        assertThat(String.join(" ", TreasuryLedgerMapper.class.getMethod("lockCommissionFundsEvent", Long.class)
                .getAnnotation(Select.class).value())).contains("nx_commission_event", "FOR UPDATE", "is_deleted=0");
        assertThat(String.join(" ", TreasuryLedgerMapper.class.getMethod("lockCommissionWallet", Long.class, String.class)
                .getAnnotation(Select.class).value())).contains("nx_user_wallet", "FOR UPDATE");
        assertThat(String.join(" ", TreasuryLedgerMapper.class.getMethod("adjustCommissionWallet", Long.class,
                String.class, BigDecimal.class).getAnnotation(Update.class).value()))
                .contains("version=version+1", "+ #{delta} >= 0", "usdt_available", "nex_available");
        assertThat(String.join(" ", TreasuryLedgerMapper.class.getMethod("closeCommissionAccrual", Long.class,
                Long.class, String.class).getAnnotation(Update.class).value()))
                .contains("status='CANCELLED'", "status='PENDING'", "direction='IN'", "asset=#{asset}",
                        "commission_event_id=#{eventId}", "F2-NETWORK-", "F1-VRANKREWARD-REISSUE-", "F5-REISSUE-")
                .doesNotContain("LIKE", "balance_after=");
        for (String method : java.util.List.of("releaseCommissionFunds", "reverseCommissionFunds")) {
            assertThat(MybatisTreasuryLedgerRepository.class.getMethod(method, Long.class)
                    .getAnnotation(Transactional.class).rollbackFor()).contains(Exception.class);
        }
    }
}
