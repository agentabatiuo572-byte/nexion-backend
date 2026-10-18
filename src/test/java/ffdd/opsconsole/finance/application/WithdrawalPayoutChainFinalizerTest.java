package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.finance.cregis.BscDepositProof;
import ffdd.opsconsole.finance.mapper.WithdrawalPayoutMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class WithdrawalPayoutChainFinalizerTest {
    final WithdrawalPayoutMapper db = mock(WithdrawalPayoutMapper.class);
    final AuditLogService audit = mock(AuditLogService.class);
    final TreasuryLedgerPostingFacade ledger = mock(TreasuryLedgerPostingFacade.class);
    final SupportWithdrawalFundSourceService funds = mock(SupportWithdrawalFundSourceService.class);
    final ffdd.opsconsole.shared.outbox.EventOutboxService outbox = mock(ffdd.opsconsole.shared.outbox.EventOutboxService.class);
    final WithdrawalPayoutFinalizer service = new WithdrawalPayoutFinalizer(db,audit,ledger,Clock.systemUTC(),funds,outbox);
    final String tx = "0x"+"a".repeat(64);
    final WithdrawalPayoutMapper.PayoutRow row = new WithdrawalPayoutMapper.PayoutRow("WD-chain",42L,"USDT-BEP20",
            "0x"+"b".repeat(40),BigDecimal.TEN,new BigDecimal("9"),BigDecimal.ZERO,"SENT",null,9L,"WD-chain","provider",1);

    @Test void providerSuccessAndFourteenConfirmationsCannotSettleReservedCash() {
        assertThatThrownBy(() -> service.terminal(row,9,"provider","fake","hash","CONFIRMED",tx,null))
                .hasMessage("CREGIS_CHAIN_PROOF_REQUIRED");
        assertThatThrownBy(() -> service.confirmProviderChain(row,tx,new BscDepositProof.Proof(1,100,"hash",14),15))
                .hasMessage("CREGIS_CHAIN_PROOF_REQUIRED");
        verifyNoInteractions(db,audit,funds,ledger);
    }

    @Test void canonicalProofUsesExistingReservationSettlementAndRequiredProofAudit() {
        when(db.lockCashCustomer(42L)).thenReturn(42L);
        when(funds.hasCashReservation(row.withdrawalNo(),42L,BigDecimal.TEN,BigDecimal.ZERO)).thenReturn(true);
        var before = mock(SupportWithdrawalFundSourceService.Before.class);
        when(funds.begin(42)).thenReturn(before);
        when(db.insertPayoutLedger(anyString(),eq("WD-chain"),eq(9L),anyString(),eq("CONFIRMED"),
                eq("provider"),eq(new BigDecimal("9")),eq(tx),anyString(),any())).thenReturn(1);
        when(db.terminalOrder(eq("WD-chain"),eq(9L),eq("CONFIRMED"),eq(tx),isNull(),any())).thenReturn(1);
        when(db.settlePending(eq(42L),eq(BigDecimal.TEN),any())).thenReturn(1);
        service.confirmProviderChain(row,tx,new BscDepositProof.Proof(1,100,"hash",15),15);
        verify(audit).recordRequired(argThat(entry -> "D5_WITHDRAWAL_PAYOUT_CHAIN_PROOF".equals(entry.getAction())));
        verify(funds).confirmed(eq(before),eq("WD-chain"),any(LocalDateTime.class));
        verify(db,never()).refundPending(any(),any(),any(),any());
        verify(outbox).publish(eq("WITHDRAWAL"),eq("WD-chain"),eq("withdraw.confirmed"),
                argThat(value -> value instanceof java.util.Map<?,?> payload && tx.equals(payload.get("chain_tx_hash"))
                        && payload.containsKey("confirmed_at")));
    }

    @Test void manualConfirmationCannotBypassProviderProof() {
        when(db.payout("WD-chain")).thenReturn(row);
        assertThatThrownBy(() -> service.settleManualConfirmation("WD-chain",42L,BigDecimal.TEN,BigDecimal.ZERO,LocalDateTime.now()))
                .hasMessage("PROVIDER_CHAIN_CONFIRMATION_REQUIRED");
        verify(db,never()).settlePending(any(),any(),any());
    }

    @Test void lateFailureCannotRefundAfterSuccessEvidenceWasDurablyObserved() {
        when(db.lockCashCustomer(42L)).thenReturn(42L);
        when(db.hasProviderSuccessEvidence("WD-chain")).thenReturn(true);
        assertThatThrownBy(() -> service.terminal(row,9,"provider","failure","hash","FAILED",null,"CREGIS_REJECTED"))
                .hasMessage("CREGIS_FAILURE_CONFLICTS_WITH_SUCCESS_EVIDENCE");
        verifyNoInteractions(funds,ledger,outbox);
        verify(db,never()).terminalOrder(any(),anyLong(),any(),any(),any(),any());
    }
}
