package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.mapper.WithdrawalPayoutMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class WithdrawalCashClosureTest {
    private final WithdrawalPayoutMapper mapper=mock(WithdrawalPayoutMapper.class);
    private final SupportWithdrawalFundSourceService funds=mock(SupportWithdrawalFundSourceService.class);
    private final LocalDateTime at=LocalDateTime.of(2026,10,11,0,0);
    @BeforeEach void cashCustomerExists() { when(mapper.lockCashCustomer(7L)).thenReturn(7L); }
    @Test void providerSuccessCannotClearAnotherOrdersPendingForAnUnreservedOldOrder() {
        unreservedTerminal("CONFIRMED");
    }
    @Test void providerFailureCannotReturnAnotherOrdersPendingForAnUnreservedOldOrder() {
        unreservedTerminal("FAILED");
    }
    private void unreservedTerminal(String status) {
        var finalizer=new WithdrawalPayoutFinalizer(mapper,mock(AuditLogService.class),mock(TreasuryLedgerPostingFacade.class),Clock.systemUTC(),funds);
        var row=new WithdrawalPayoutMapper.PayoutRow("OLD",7L,"USDT-TRC20","address",new BigDecimal("100"),new BigDecimal("90"),BigDecimal.ZERO,"SENT",at,9L,"OLD","provider",1);
        assertThatThrownBy(()->finalizer.terminal(row,9L,"provider","event","hash",status,null,"failure"))
                .isInstanceOf(ffdd.opsconsole.shared.exception.BizException.class)
                .hasMessage("该笔提现的预留资金尚未核实，请复核后重试");
        verify(mapper,never()).insertPayoutLedger(any(),any(),anyLong(),any(),any(),any(),any(),any(),any(),any());
        verify(mapper,never()).settlePending(any(),any(),any());
        verify(mapper,never()).refundPending(any(),any(),any(),any());
        verify(funds,never()).begin(anyLong());
    }
    @Test void manualSuccessClearsGrossOnceAndDoesNotBurnNexAgain() throws Exception {
        var pending=new AtomicReference<>(new BigDecimal("200"));
        when(funds.hasCashReservation("WD1",7L,new BigDecimal("100"),new BigDecimal("5"))).thenReturn(true);
        when(mapper.settlePending(7L,new BigDecimal("100"),at)).thenAnswer(i->{pending.set(pending.get().subtract(i.getArgument(1)));return 1;});
        settle("WD1",new BigDecimal("100"),new BigDecimal("5"));
        assertThat(pending.get()).isEqualByComparingTo("100");
        verify(mapper).settlePending(7L,new BigDecimal("100"),at);
        verify(mapper,never()).refundPending(any(),any(),any(),any());
        verify(funds).confirmed(any(),eq("WD1"),eq(at));
    }
    @Test void unrelatedPendingIsNeverTakenForAnUnprovenOldOrder() throws Exception {
        settle("OLD",new BigDecimal("100"),BigDecimal.ZERO);
        verify(mapper,never()).settlePending(any(),any(),any());
        verify(funds,never()).begin(anyLong());
        verify(funds,never()).confirmed(any(),anyString(),any());
    }
    @Test void walletConflictIsNotRecordedAsAConfirmedCashEffect() throws Exception {
        when(funds.hasCashReservation("WD1",7L,new BigDecimal("100"),BigDecimal.ZERO)).thenReturn(true);
        assertThatThrownBy(()->settle("WD1",new BigDecimal("100"),BigDecimal.ZERO)).isInstanceOf(ffdd.opsconsole.shared.exception.BizException.class);
        verify(funds,never()).confirmed(any(),anyString(),any());
    }
    @Test void genuineOldReservationCanReturnCashWithoutAnyOriginalFundReceipt() throws Exception {
        var f=new TerminalFixture("FAILED");f.legacyReservation();
        assertThat(f.finalizer.terminal(f.row,9,"provider","event","hash","FAILED",null,"failure")).isTrue();
        assertThat(f.pending.get()).isEqualByComparingTo("100");
        assertThat(f.available.get()).isEqualByComparingTo("1000");
        assertThat(f.receipts).hasSize(1);
        assertThat(f.source.receipt().sourceProofJson()).contains("UNKNOWN");
    }
    @Test void genuineOldReservationCanConfirmCashWithoutAnyOriginalFundReceipt() throws Exception {
        var f=new TerminalFixture("CONFIRMED");f.legacyReservation();
        assertThat(f.finalizer.terminal(f.row,9,"provider","event","hash","CONFIRMED","txid",null)).isTrue();
        assertThat(f.pending.get()).isEqualByComparingTo("100");
        assertThat(f.available.get()).isEqualByComparingTo("900");
        assertThat(f.receipts).hasSize(1);
    }
    @Test void bankQuoteAndOrderReservationCanReturnWithoutAnAppAttempt() throws Exception {
        var f=new TerminalFixture("FAILED");f.source.order.remove("attemptStatus");
        f.source.order.put("chain","BANK-VND");
        when(f.source.mapper.fundReservationLedgers("WD1")).thenReturn(java.util.List.of(
                SupportWithdrawalFundSourceServiceTest.ledger("WD1:USDT:PRINCIPAL","WITHDRAW_NET_PRINCIPAL","USDT",new BigDecimal("90")),
                SupportWithdrawalFundSourceServiceTest.ledger("WD1:USDT:BANK_FEE","WITHDRAW_BANK_FEE","USDT",new BigDecimal("10"))));
        when(f.source.mapper.fundBankReservation("WD1")).thenReturn(java.util.Map.of(
                "withdrawalNo","WD1","userId",7L,"quoteNo","Q1","amount",new BigDecimal("100"),
                "fee",new BigDecimal("10"),"net",new BigDecimal("90")));
        var bankRow=new WithdrawalPayoutMapper.PayoutRow("WD1",7L,"BANK-VND","address",new BigDecimal("100"),new BigDecimal("90"),BigDecimal.ZERO,"SENT",at,9L,"WD1","hdpay",1);
        assertThat(f.finalizer.terminal(bankRow,9,"hdpay","event","hash","FAILED",null,"failure")).isTrue();
        assertThat(f.pending.get()).isEqualByComparingTo("100");
        verify(f.ledger).reverseLegacyBankWithdrawalReserve("WD1",new BigDecimal("100"),at);
    }
    @Test void callbackReplayCannotClearTheSecondOrdersPending() {
        var f=new TerminalFixture("CONFIRMED");
        f.finalizer.terminal(f.row,9,"provider","event","hash","CONFIRMED","txid",null);
        f.finalizer.terminal(f.row,9,"provider","event","hash","CONFIRMED","txid",null);
        assertThat(f.pending.get()).isEqualByComparingTo("100");
        assertThat(f.receipts).hasSize(1);
        verify(f.source.mapper).settlePending(7L,new BigDecimal("100"),at);
    }
    @Test void zeroCashCasRollsBackOrderCallbackAndReceiptInTheSpringTransaction() {
        var f=new TerminalFixture("CONFIRMED");
        when(f.source.mapper.settlePending(7L,new BigDecimal("100"),at)).thenReturn(0);
        assertThatThrownBy(()->f.finalizer.terminal(f.row,9,"provider","event","hash","CONFIRMED","txid",null))
                .isInstanceOf(ffdd.opsconsole.shared.exception.BizException.class);
        f.assertRolledBack();
    }
    @Test void requiredAuditFailureRollsBackSuccessfulCashOrderCallbackAndReceipt() {
        var f=new TerminalFixture("CONFIRMED");
        doThrow(new IllegalStateException("audit unavailable")).when(f.audit).recordRequired(any());
        assertThatThrownBy(()->f.finalizer.terminal(f.row,9,"provider","event","hash","CONFIRMED","txid",null))
                .isInstanceOf(IllegalStateException.class).hasMessage("audit unavailable");
        f.assertRolledBack();
    }
    @Test void failedReturnAuditRollsBackBothCashAndRefundLedger() {
        var f=new TerminalFixture("FAILED");
        doThrow(new IllegalStateException("audit unavailable")).when(f.audit).recordRequired(any());
        assertThatThrownBy(()->f.finalizer.terminal(f.row,9,"provider","event","hash","FAILED",null,"failure"))
                .isInstanceOf(IllegalStateException.class);
        f.assertRolledBack();
    }
    @Test void sourceProofLocksBeforeWalletAndOrderCashMutation() {
        var f=new TerminalFixture("CONFIRMED");
        f.finalizer.terminal(f.row,9,"provider","event","hash","CONFIRMED","txid",null);
        var sequence=inOrder(f.source.mapper,f.source.journal);
        sequence.verify(f.source.mapper).lockCashCustomer(7L);
        sequence.verify(f.source.mapper).fundOrder("WD1");
        sequence.verify(f.source.mapper).fundReservationLedgers("WD1");
        sequence.verify(f.source.journal).lockWallet(7L);
        sequence.verify(f.source.mapper).terminalOrder(eq("WD1"),eq(9L),eq("CONFIRMED"),eq("txid"),isNull(),eq(at));
    }

    /** Exercises real transaction advice; storage is an explicit rollback-capable double, not SQL. */
    static class MemoryTransactionManager extends org.springframework.transaction.support.AbstractPlatformTransactionManager {
        private final Runnable begin, rollback;
        MemoryTransactionManager(Runnable begin,Runnable rollback){this.begin=begin;this.rollback=rollback;}
        @Override protected Object doGetTransaction(){return new Object();}
        @Override protected void doBegin(Object transaction,org.springframework.transaction.TransactionDefinition definition){begin.run();}
        @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status){}
        @Override protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status){rollback.run();}
    }
    static <T> T transactionProxy(T target,org.springframework.transaction.PlatformTransactionManager manager) {
        var proxy=new org.springframework.aop.framework.ProxyFactory(target);proxy.setProxyTargetClass(true);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        @SuppressWarnings("unchecked") T value=(T)proxy.getProxy();return value;
    }
    private class TerminalFixture {
        final SupportWithdrawalFundSourceServiceTest.Fixture source;
        final AuditLogService audit=mock(AuditLogService.class);
        final TreasuryLedgerPostingFacade ledger=mock(TreasuryLedgerPostingFacade.class);
        final AtomicReference<BigDecimal> pending=new AtomicReference<>(new BigDecimal("200"));
        final AtomicReference<BigDecimal> available=new AtomicReference<>(new BigDecimal("900"));
        final java.util.Map<String,String> callbacks=new java.util.HashMap<>();
        final java.util.List<String> receipts=new java.util.ArrayList<>(),refunds=new java.util.ArrayList<>();
        final WithdrawalPayoutFinalizer finalizer;
        final WithdrawalPayoutMapper.PayoutRow row=new WithdrawalPayoutMapper.PayoutRow("WD1",7L,"USDT-TRC20","address",new BigDecimal("100"),new BigDecimal("90"),BigDecimal.ZERO,"SENT",at,9L,"WD1","provider",1);
        TerminalFixture(String status) {
            source=new SupportWithdrawalFundSourceServiceTest.Fixture("FAILED".equals(status)?"RETURN":"CONFIRM",BigDecimal.ZERO);
            source.before.put("pendingWithdraw",new BigDecimal("200"));source.after.put("pendingWithdraw",new BigDecimal("100"));
            source.raw.put("oldPendingWithdraw",new BigDecimal("200"));source.raw.put("newPendingWithdraw",new BigDecimal("100"));
            source.order.put("status","SENT");
            when(source.mapper.lockCashCustomer(7L)).thenReturn(7L);
            when(source.mapper.payoutLedgerPayloadHash(anyString())).thenAnswer(i->callbacks.get(i.getArgument(0)));
            when(source.mapper.insertPayoutLedger(any(),any(),anyLong(),any(),any(),any(),any(),any(),any(),any()))
                    .thenAnswer(i->{callbacks.put(i.getArgument(0),i.getArgument(8));return 1;});
            when(source.mapper.terminalOrder(eq("WD1"),eq(9L),anyString(),any(),any(),eq(at))).thenAnswer(i->{
                if(!"SENT".equals(source.order.get("status")))return 0;
                source.order.put("status",i.getArgument(2));source.order.put("completedAt",at);return 1;});
            when(source.mapper.settlePending(7L,new BigDecimal("100"),at)).thenAnswer(i->{pending.set(pending.get().subtract(new BigDecimal("100")));return 1;});
            when(source.mapper.refundPending(7L,new BigDecimal("100"),BigDecimal.ZERO,at)).thenAnswer(i->{pending.set(pending.get().subtract(new BigDecimal("100")));available.set(available.get().add(new BigDecimal("100")));return 1;});
            doAnswer(i->{refunds.add(i.getArgument(0));return null;}).when(ledger).postLedgerEntry(any(),any(),any(),any(),any(),any(),any(),any());
            doAnswer(i->{var input=i.getArgument(1,ffdd.opsconsole.finance.facade.SupportFundJournalFacade.ReceiptInput.class);
                receipts.add(input.operationKey());return new ffdd.opsconsole.finance.facade.SupportFundJournalFacade.BoundReceipt(1,input.operationKey(),"a".repeat(64),1);})
                    .when(source.fund).bind(any(),any());
            var target=new WithdrawalPayoutFinalizer(source.mapper,audit,ledger,Clock.fixed(at.toInstant(ZoneOffset.UTC),ZoneOffset.UTC),source.service);
            var manager=new MemoryTransactionManager(()->{},()->{pending.set(new BigDecimal("200"));available.set(new BigDecimal("900"));
                source.order.put("status","SENT");callbacks.clear();receipts.clear();refunds.clear();});
            finalizer=transactionProxy(target,manager);
        }
        void legacyReservation() throws Exception {
            source.order.remove("attemptStatus");
            String detail=source.json.writeValueAsString(java.util.Map.of("withdrawal_id","WD1","chain","USDT-TRC20",
                    "amount_usdt",new BigDecimal("100"),"net_receive",new BigDecimal("90"),"actual_fee",new BigDecimal("10"),"nex_burned",BigDecimal.ZERO));
            when(source.mapper.fundSubmissionAudits("WD1")).thenReturn(java.util.List.of(java.util.Map.of("userId",7L,"actorId",7L,
                    "actorType","USER","result","SUCCESS","detailJson",detail)));
        }
        void assertRolledBack(){assertThat(pending.get()).isEqualByComparingTo("200");assertThat(available.get()).isEqualByComparingTo("900");
            assertThat(source.order.get("status")).isEqualTo("SENT");assertThat(callbacks).isEmpty();assertThat(receipts).isEmpty();assertThat(refunds).isEmpty();}
    }
    private void settle(String no,BigDecimal gross,BigDecimal nex) throws Exception {
        var constructor=WithdrawalPayoutFinalizer.class.getConstructors()[0];
        Object[] args=constructor.getParameterCount()==4?new Object[]{mapper,mock(AuditLogService.class),mock(TreasuryLedgerPostingFacade.class),Clock.systemUTC()}:
                new Object[]{mapper,mock(AuditLogService.class),mock(TreasuryLedgerPostingFacade.class),Clock.systemUTC(),funds};
        var finalizer=constructor.newInstance(args);
        try {
            WithdrawalPayoutFinalizer.class.getMethod("settleManualConfirmation",String.class,Long.class,BigDecimal.class,BigDecimal.class,LocalDateTime.class)
                    .invoke(finalizer,no,7L,gross,nex,at);
        } catch(NoSuchMethodException oldSource) { /* Original D2 has no finalizer cash closure. */ }
        catch(java.lang.reflect.InvocationTargetException failure){if(failure.getCause() instanceof RuntimeException runtime)throw runtime;throw failure;}
    }
}
