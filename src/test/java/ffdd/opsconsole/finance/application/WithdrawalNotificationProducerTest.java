package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.finance.domain.WithdrawalOrderRepository;
import ffdd.opsconsole.finance.mapper.WithdrawalPayoutMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WithdrawalNotificationProducerTest {
    final WithdrawalPayoutMapper mapper = mock(WithdrawalPayoutMapper.class);
    final EventOutboxService outbox = mock(EventOutboxService.class);
    final WithdrawalPayoutFinalizer finalizer = new WithdrawalPayoutFinalizer(mapper,
            mock(AuditLogService.class), mock(TreasuryLedgerPostingFacade.class), Clock.systemUTC(), outbox);
    final WithdrawalPayoutMapper.PayoutRow row = new WithdrawalPayoutMapper.PayoutRow("WD-1",7L,"USDT-TRC20","address",
            BigDecimal.TEN,BigDecimal.TEN,BigDecimal.ZERO,"SENT",LocalDateTime.now(),1L,"WD-1","provider",1);

    @Test
    void chainSuccessPublishesOnlyAfterSettlementAndReplayDoesNotPublishAgain() {
        when(mapper.insertPayoutLedger(anyString(),anyString(),anyLong(),anyString(),anyString(),anyString(),any(),any(),anyString(),any())).thenReturn(1);
        when(mapper.terminalOrder(anyString(),anyLong(),anyString(),any(),any(),any())).thenReturn(1);
        when(mapper.settlePending(eq(7L),eq(BigDecimal.TEN),any())).thenReturn(1);
        assertThat(finalizer.terminal(row,1L,"provider","event-1","hash","CONFIRMED","txhash",null)).isTrue();
        var order = inOrder(mapper,outbox);
        order.verify(mapper).settlePending(eq(7L),eq(BigDecimal.TEN),any());
        order.verify(outbox).publish(eq("WITHDRAWAL"),eq("WD-1"),eq("withdraw.confirmed"),argThat(p ->
                ((Map<?,?>)p).get("user_id").equals(7L) && ((Map<?,?>)p).get("chain_tx_hash").equals("txhash")));
        when(mapper.payoutLedgerPayloadHash("event-1")).thenReturn("hash");
        assertThat(finalizer.terminal(row,1L,"provider","event-1","hash","CONFIRMED","txhash",null)).isTrue();
        verify(outbox,times(1)).publish(anyString(),anyString(),anyString(),any());
    }

    @Test
    void failedStateChangeAndInternalRetryProduceNoPublicSuccess() {
        assertThat(finalizer.submitted(row,1L,"provider")).isFalse();
        assertThat(finalizer.retry(row,"temporary-provider-error")).isFalse();
        verifyNoInteractions(outbox);
    }

    @Test
    void c2FreezesAndRestoresOnlyRowsActuallyChangedInTheTransaction() {
        var repository = mock(WithdrawalOrderRepository.class);
        var service = new FinanceWithdrawalControlFacadeAdapter(repository,mock(AuditLogService.class),outbox);
        when(repository.lockUserStatusWithdrawalNos(7L,false)).thenReturn(List.of("WD-1","WD-2"));
        when(repository.freezePendingByUserId(7L,"reason")).thenReturn(2);
        assertThat(service.freezePendingWithdrawalsForUser(7L,"reason","admin")).isEqualTo(2);
        verify(outbox).publish(eq("WITHDRAWAL"),eq("WD-1"),eq("withdraw.account_frozen"),any());
        verify(outbox).publish(eq("WITHDRAWAL"),eq("WD-2"),eq("withdraw.account_frozen"),any());
        when(repository.lockUserStatusWithdrawalNos(7L,true)).thenReturn(List.of("WD-1","WD-2"));
        when(repository.restoreFrozenByUserStatus(7L)).thenReturn(2);
        assertThat(service.restoreWithdrawalsFrozenByUserStatus(7L,"reason","admin")).isEqualTo(2);
        verify(outbox).publish(eq("WITHDRAWAL"),eq("WD-1"),eq("withdraw.account_restored"),any());
        when(repository.freezePendingByUserId(7L,"reason")).thenReturn(0);
        service.freezePendingWithdrawalsForUser(7L,"reason","admin");
        verify(outbox,times(4)).publish(anyString(),anyString(),anyString(),any());
    }
}
