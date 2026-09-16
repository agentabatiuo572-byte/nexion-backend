package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.hdpay.*;
import ffdd.opsconsole.finance.mapper.*;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.time.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HdPayPayoutTransactionsTest {
    final BankWithdrawalMapper bank = mock(BankWithdrawalMapper.class);
    final AppWithdrawalMapper users = mock(AppWithdrawalMapper.class);
    final WithdrawalPayoutMapper canonical = mock(WithdrawalPayoutMapper.class);
    final WithdrawalPayoutFinalizer finalizer = mock(WithdrawalPayoutFinalizer.class);
    final FinanceSensitiveDataCipher cipher = mock(FinanceSensitiveDataCipher.class);
    final EventOutboxService outbox = mock(EventOutboxService.class);
    final Clock clock = Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC);
    final HdPayProperties transport = mock(HdPayProperties.class);
    final HdPayPayoutProperties properties = mock(HdPayPayoutProperties.class);
    final PayoutVndConfigService config = mock(PayoutVndConfigService.class);
    final HdPayPayoutTransactions service = new HdPayPayoutTransactions(bank, users, canonical, finalizer, cipher,
            transport, properties, config,
            mock(OpsFinanceService.class), mock(AuditLogService.class), outbox, clock);
    final String no = "WD-TEST", qn = "BQ-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    final LocalDateTime now = LocalDateTime.now(clock);
    final BankWithdrawalMapper.Quote quote = new BankWithdrawalMapper.Quote(qn, 71L, "BNK-fixture", 1L,
            "VCB", "******6789", "encrypted-fixture", bd("100"), bd("1"), bd("99"), bd("25000"),
            bd("2475000"), 1L, "d5-v1", now, now.plusMinutes(5), no);
    static BigDecimal bd(String n) { return new BigDecimal(n); }
    BankWithdrawalMapper.Order order(String state) { return new BankWithdrawalMapper.Order(no, qn, 71L, state, 123L, 1, null); }
    WithdrawalPayoutMapper.PayoutRow row(String status, Long user, String amount, String net) {
        return new WithdrawalPayoutMapper.PayoutRow(no, user, "BANK-VND", "BANK-VND:BNK-fixture", bd(amount), bd(net),
                BigDecimal.ZERO, status, now, 123L, no, "hdpay", 1);
    }
    @Test void prepareNormalizesHistoricalBankCodeToEmptyButRetainsAllDispatchGates() {
        when(users.lockActiveUser(71L)).thenReturn(71L);
        when(bank.lockOrder(no)).thenReturn(order("READY"));
        assertNull(service.prepare(no));
        verify(bank, never()).dispatch(anyString(), any());
        when(properties.ready(transport)).thenReturn(true);
        when(config.overview()).thenReturn(ffdd.opsconsole.shared.api.ApiResult.ok(java.util.Map.of("channelEnabled", true, "providerReady", true)));
        when(bank.lockBeneficiary(71L)).thenReturn(new BankWithdrawalMapper.Beneficiary(71L,"BNK-fixture","","****6789","cipher",now.plusHours(24),now.plusDays(7),1L));
        when(canonical.payout(no)).thenReturn(row("REVIEW_PASSED",71L,"100","99"));
        when(bank.processing(eq(no), any())).thenReturn(1); when(bank.dispatch(eq(no), any())).thenReturn(1);
        var request = service.prepare(no);
        assertNotNull(request); assertEquals("", request.bankCode());
        assertEquals("0123456789", request.account()); assertEquals("NGUYEN VAN A", request.holder());
        assertEquals(quote.amountVnd(), request.amount());
        verify(bank).dispatch(eq(no), any()); verifyNoInteractions(finalizer, outbox);
        clearInvocations(bank);
        when(bank.lockBeneficiary(71L)).thenReturn(new BankWithdrawalMapper.Beneficiary(71L,"BNK-changed","","****6789","cipher",now,now.plusDays(7),2L));
        assertNull(service.prepare(no)); verify(bank,never()).dispatch(anyString(),any());
    }
    HdPayPayoutGateway.Order response(int status) {
        return new HdPayPayoutGateway.Order(no, 123L, status, quote.amountVnd(), "0123456789", "NGUYEN VAN A", "2");
    }
    @BeforeEach void setup() {
        when(bank.order(no)).thenReturn(order("PENDING"));
        when(bank.lockOrder(no)).thenReturn(order("PENDING"));
        when(bank.quote(qn)).thenReturn(quote);
        when(cipher.decrypt(eq("encrypted-fixture"), anyString())).thenReturn("0123456789\nNGUYEN VAN A");
        when(canonical.payout(no)).thenReturn(row("SENT", 71L, "100", "99"));
        when(finalizer.terminal(any(), anyLong(), anyString(), anyString(), anyString(), anyString(), any(), any())).thenReturn(true);
    }
    @Test void pendingResultNeverSettlesOrRefunds() {
        service.reconcile(no, response(2));
        verifyNoInteractions(finalizer, outbox);
        verify(bank).progress(eq(no), eq("PENDING"), eq(123L), eq(2), isNull(), any());
    }
    @Test void successUsesCanonicalFinalizerAndDuplicateCannotSettleAgain() {
        service.reconcile(no, response(3));
        when(bank.lockOrder(no)).thenReturn(order("PAID"));
        when(canonical.payout(no)).thenReturn(row("CONFIRMED", 71L, "100", "99"));
        service.reconcile(no, response(3));
        verify(finalizer, times(1)).terminal(any(), eq(123L), eq("hdpay"), anyString(), anyString(), eq("CONFIRMED"), isNull(), isNull());
        verify(outbox, times(1)).publish(eq("WITHDRAWAL"), eq(no), eq("withdraw.confirmed"), any());
    }
    @Test void definitiveFailureRefundsOnceThroughSharedAuthority() {
        service.reconcile(no, response(5));
        when(bank.lockOrder(no)).thenReturn(order("FAILED"));
        when(canonical.payout(no)).thenReturn(row("FAILED", 71L, "100", "99"));
        service.reconcile(no, response(5));
        verify(finalizer, times(1)).terminal(any(), eq(123L), eq("hdpay"), anyString(), anyString(), eq("FAILED"), isNull(), eq("HDPAY_PAYOUT_FAILED"));
    }
    @Test void providerConfirmedReturnRefundsThroughSharedAuthority() {
        service.reconcile(no, response(4));
        verify(finalizer).terminal(any(), eq(123L), eq("hdpay"), anyString(), anyString(), eq("FAILED"), isNull(), eq("HDPAY_PAYOUT_RETURNED"));
        verify(outbox).publish(eq("WITHDRAWAL"), eq(no), eq("withdraw.refunded"), any());
    }
    @Test void callbackAcknowledgesDurableEvidenceButNeverSettles() {
        var callback = new HdPayPayoutCallbackVerifier.Callback(no, 123L, 3, quote.amountVnd(), "fixture-digest");
        assertEquals("success", service.accept(callback));
        verify(bank).callback(callback);
        verifyNoInteractions(finalizer, outbox);
    }
    @Test void contradictoryCallbackEvidenceBlocksAQuerySuccess() {
        when(bank.conflictingCallbacks(no, 123L, quote.amountVnd(), 3)).thenReturn(1);
        service.reconcile(no, response(3));
        verify(bank).hold(eq(no), eq("BANK_PAYOUT_EVIDENCE_CONFLICT"), any());
        verifyNoInteractions(finalizer, outbox);
    }
    @Test void queryRecipientMismatchNeverMovesMoney() {
        service.reconcile(no, new HdPayPayoutGateway.Order(no, 123L, 3, quote.amountVnd(), "9999999999", "NGUYEN VAN A", "2"));
        verify(bank).hold(eq(no), eq("BANK_PAYOUT_EVIDENCE_CONFLICT"), any());
        verifyNoInteractions(finalizer, outbox);
    }
    @Test void canonicalWrongOwnerOrMoneyCannotSettleQuotedBankPayment() {
        for (var bad : java.util.List.of(row("SENT", 72L, "100", "99"), row("SENT", 71L, "200", "99"), row("SENT", 71L, "100", "98"))) {
            when(canonical.payout(no)).thenReturn(bad);
            service.reconcile(no, response(3));
        }
        verify(bank, times(3)).hold(eq(no), eq("BANK_PAYOUT_SNAPSHOT_MISMATCH"), any());
        verifyNoInteractions(finalizer, outbox);
    }
    @Test void unknownTargetTypeCannotSettle() {
        service.reconcile(no, new HdPayPayoutGateway.Order(no, 123L, 3, quote.amountVnd(), "0123456789", "NGUYEN VAN A", "BANK"));
        verifyNoInteractions(finalizer, outbox);
        verify(bank).hold(eq(no), eq("BANK_PAYOUT_EVIDENCE_CONFLICT"), any());
    }
    @Test void staleRecoveryNeverChangesMoneyOrCanonicalStatus() {
        when(bank.lockOrder(no)).thenReturn(order("MANUAL_REVIEW"));
        when(bank.version(no)).thenReturn(2L);
        assertThrows(RuntimeException.class, () -> service.recover(no, 1, response(3), "admin", "verify original order"));
        verifyNoInteractions(finalizer, outbox);
        verify(bank, never()).resumeHeld(anyString(), any());
    }
}
