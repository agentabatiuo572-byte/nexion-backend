package ffdd.opsconsole.treasury.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.treasury.mapper.TreasuryLedgerMapper;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.treasury.facade.SupportCommissionCaptureFacade;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Bep20WithdrawalReserveTest {
    final TreasuryLedgerMapper mapper=mock(TreasuryLedgerMapper.class);
    final MybatisTreasuryLedgerRepository repository=new MybatisTreasuryLedgerRepository(mapper,
            mock(EventOutboxService.class),mock(SupportCommissionCaptureFacade.class));
    final BigDecimal amount=new BigDecimal("100.000000");
    final LocalDateTime now=LocalDateTime.of(2026,10,22,12,0);

    TreasuryLedgerMapper.ReserveWithdrawal order(String status,String tx,String source) {
        return new TreasuryLedgerMapper.ReserveWithdrawal(7L,"USDT-BEP20",amount,status,tx,source);
    }
    TreasuryLedgerMapper.ReserveEntry entry(String prefix,String voucher,String direction,String evidence) {
        return new TreasuryLedgerMapper.ReserveEntry(prefix+"WD1",voucher+"WD1",direction,amount,evidence,"CONFIRMED",0);
    }
    void failedWithOriginal() {
        when(mapper.lockReserveWithdrawal("WD1")).thenReturn(order("FAILED",null,"provider"));
        when(mapper.lockReserveEntries("RSV-WD-WD1","WD-WD1"))
                .thenReturn(List.of(entry("RSV-WD-","WD-","OUT","approval-1")));
        when(mapper.insertBep20ReserveReversal("RSV-WD-REV-WD1","WD-REV-WD1",amount,"reverse:WD-WD1",now)).thenReturn(1);
    }
    @Test void definiteFailureReversesTheOriginalGrossAndReplaysOnce() {
        failedWithOriginal();
        when(mapper.lockReserveEntries("RSV-WD-REV-WD1","WD-REV-WD1")).thenReturn(List.of(),
                List.of(entry("RSV-WD-REV-","WD-REV-","IN","reverse:WD-WD1")));
        repository.reverseBep20WithdrawalReserve("WD1",amount,now);
        repository.reverseBep20WithdrawalReserve("WD1",amount,now.plusSeconds(1));
        verify(mapper,times(1)).insertBep20ReserveReversal(any(),any(),any(),any(),any());
    }
    @Test void erc20UsesTheSameGrossReserveReversalAndCannotReturnAnUnknownBroadcast() {
        failedWithOriginal();
        when(mapper.lockReserveWithdrawal("WD1")).thenReturn(new TreasuryLedgerMapper.ReserveWithdrawal(7L,"USDT-ERC20",amount,"FAILED",null,"provider"));
        repository.reverseBep20WithdrawalReserve("WD1",amount,now);
        verify(mapper).insertBep20ReserveReversal(any(),any(),eq(amount),any(),any());
        when(mapper.lockReserveWithdrawal("WD1")).thenReturn(new TreasuryLedgerMapper.ReserveWithdrawal(7L,"USDT-ERC20",amount,"TX_ORPHANED",null,"provider"));
        assertThatThrownBy(()->repository.reverseBep20WithdrawalReserve("WD1",amount,now)).hasMessage("BEP20_RESERVE_RETURN_STATE_INVALID");
    }
    @Test void missingOriginalOutFailsClosed() {
        failedWithOriginal();when(mapper.lockReserveEntries("RSV-WD-WD1","WD-WD1")).thenReturn(List.of());
        assertThatThrownBy(()->repository.reverseBep20WithdrawalReserve("WD1",amount,now)).isInstanceOf(IllegalStateException.class);
        verify(mapper,never()).insertBep20ReserveReversal(any(),any(),any(),any(),any());
    }
    @ParameterizedTest @ValueSource(strings={"REVIEW_PENDING","PROCESSING","SENT","TX_ORPHANED","FROZEN","CONFIRMED","REFUNDED"})
    void nonFailureCannotManufactureAReserveReturn(String status) {
        when(mapper.lockReserveWithdrawal("WD1")).thenReturn(order(status,null,"provider"));
        assertThatThrownBy(()->repository.reverseBep20WithdrawalReserve("WD1",amount,now)).hasMessage("BEP20_RESERVE_RETURN_STATE_INVALID");
        verify(mapper,never()).lockReserveEntries(any(),any());
    }
    @Test void failedWithTransactionOrDifferentAmountIsRejected() {
        failedWithOriginal();when(mapper.lockReserveWithdrawal("WD1")).thenReturn(order("FAILED","0x"+"a".repeat(64),"provider"));
        assertThatThrownBy(()->repository.reverseBep20WithdrawalReserve("WD1",amount,now)).hasMessage("BEP20_RESERVE_RETURN_STATE_INVALID");
        when(mapper.lockReserveWithdrawal("WD1")).thenReturn(order("FAILED",null,"provider"));
        assertThatThrownBy(()->repository.reverseBep20WithdrawalReserve("WD1",new BigDecimal("99"),now)).hasMessage("BEP20_RESERVE_ORDER_AMOUNT_MISMATCH");
        verify(mapper,never()).insertBep20ReserveReversal(any(),any(),any(),any(),any());
    }
    @Test void conflictingReversalEvidenceIsNotAcceptedAsReplay() {
        failedWithOriginal();when(mapper.lockReserveEntries("RSV-WD-REV-WD1","WD-REV-WD1"))
                .thenReturn(List.of(entry("RSV-WD-REV-","WD-REV-","IN","unrelated")));
        assertThatThrownBy(()->repository.reverseBep20WithdrawalReserve("WD1",amount,now)).isInstanceOf(IllegalStateException.class);
        verify(mapper,never()).insertBep20ReserveReversal(any(),any(),any(),any(),any());
    }
    @Test void manualRefundRequiresOriginalOutBeforeTouchingWallet() {
        when(mapper.lockReserveWithdrawal("WD1")).thenReturn(order("REFUNDED",null,null));
        assertThatThrownBy(()->repository.refundWithdrawal("WD1",7L,amount,"USDT","manual return"))
                .isInstanceOf(IllegalStateException.class);
        verify(mapper,never()).releasePendingWithdrawalWithNex(any(),any(),any());
    }
    @Test void manualRefundReversesExistingOutBeforeWalletCas() {
        failedWithOriginal();when(mapper.lockReserveWithdrawal("WD1")).thenReturn(order("REFUNDED",null,null));
        when(mapper.insertBep20ReserveReversal(any(),any(),eq(amount),eq("reverse:WD-WD1"),any())).thenReturn(1);
        // Stop at the wallet CAS: the transaction must include this failure and the reserve return.
        assertThatThrownBy(()->repository.refundWithdrawal("WD1",7L,amount,"USDT","manual return"))
                .hasMessage("WITHDRAWAL_PENDING_FUNDS_INCONSISTENT");
        var sequence=inOrder(mapper);
        sequence.verify(mapper).insertBep20ReserveReversal(any(),any(),eq(amount),eq("reverse:WD-WD1"),any());
        sequence.verify(mapper).releasePendingWithdrawalWithNex(7L,amount,BigDecimal.ZERO.setScale(6));
    }
}
