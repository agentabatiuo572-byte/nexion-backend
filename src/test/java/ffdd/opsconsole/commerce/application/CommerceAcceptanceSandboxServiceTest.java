package ffdd.opsconsole.commerce.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.commerce.mapper.CommerceAcceptanceSandboxMapper;
import ffdd.opsconsole.commerce.mapper.CommerceAcceptanceSandboxMapper.CallbackWrite;
import ffdd.opsconsole.commerce.mapper.CommerceAcceptanceSandboxMapper.InventoryRow;
import ffdd.opsconsole.commerce.mapper.CommerceAcceptanceSandboxMapper.SandboxOrder;
import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import ffdd.opsconsole.finance.mapper.FundsSandboxMapper;
import ffdd.opsconsole.finance.mapper.FundsSandboxMapper.LedgerWrite;
import ffdd.opsconsole.finance.mapper.FundsSandboxMapper.WalletRow;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CommerceAcceptanceSandboxServiceTest {
    private static final String RUN_ID = "test-run-0001";
    private final CommerceAcceptanceSandboxMapper mapper = mock(CommerceAcceptanceSandboxMapper.class);
    private final FundsSandboxMapper funds = mock(FundsSandboxMapper.class);
    private final FundsSandboxProfileGuard fundsGuard = mock(FundsSandboxProfileGuard.class);
    private final CommerceAcceptanceSandboxService service = new CommerceAcceptanceSandboxService(
            mapper, funds, fundsGuard, Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC),
            new CommerceAcceptanceRun(RUN_ID));

    CommerceAcceptanceSandboxServiceTest() {
        when(fundsGuard.isLocalSandboxEnabled()).thenReturn(true);
        when(funds.isSandboxUser(anyLong())).thenReturn(1);
        when(mapper.insertAudit(any())).thenReturn(1);
    }

    @Test
    void paymentSuccessDebitsOnlyTheFundsSandboxWalletWritesLedgerAndUsesOrderCas() {
        SandboxOrder sandbox = sandbox("ORD-SBX-1", 0L, "PENDING_PAYMENT", false, false);
        when(mapper.lockSandboxOrder(RUN_ID, "ORD-SBX-1")).thenReturn(sandbox);
        when(mapper.findCallback(RUN_ID, "evt-payment-ok")).thenReturn(null);
        when(funds.insertWalletIfAbsent(RUN_ID, 41L)).thenReturn(1);
        when(funds.lockWallet(RUN_ID, 41L)).thenReturn(new WalletRow(41L, money("20"), money("0"), 3L));
        when(funds.reserveWallet(RUN_ID, 41L, money("12"), 3L)).thenReturn(1);
        when(funds.consumeReservedWallet(RUN_ID, 41L, money("12"), 4L)).thenReturn(1);
        when(mapper.transitionSandboxOrder(RUN_ID, "ORD-SBX-1", 0L, "PENDING_PAYMENT", "PAID", true, false)).thenReturn(1);
        when(funds.insertLedger(eq(RUN_ID), any())).thenReturn(1);
        when(mapper.insertCallback(any())).thenReturn(1);

        var result = service.applyCallback("ORD-SBX-1", "evt-payment-ok", "PAYMENT_SUCCEEDED", 0L);

        assertThat(result.canonicalStatus()).isEqualTo("paid");
        assertThat(result.walletAfter()).isEqualByComparingTo("8.000000");
        verify(funds).reserveWallet(RUN_ID, 41L, money("12"), 3L);
        verify(funds).consumeReservedWallet(RUN_ID, 41L, money("12"), 4L);
        verify(mapper).transitionSandboxOrder(RUN_ID, "ORD-SBX-1", 0L, "PENDING_PAYMENT", "PAID", true, false);
        ArgumentCaptor<LedgerWrite> ledger = ArgumentCaptor.forClass(LedgerWrite.class);
        verify(funds).insertLedger(eq(RUN_ID), ledger.capture());
        assertThat(ledger.getValue().entryRole()).isEqualTo("COMMERCE_PAYMENT_DEBIT");
        assertThat(ledger.getValue().direction()).isEqualTo("OUT");
        assertThat(ledger.getValue().availableAfter()).isEqualByComparingTo("8.000000");
        assertThat(ledger.getValue().reservedAfter()).isEqualByComparingTo("0.000000");
        ArgumentCaptor<CallbackWrite> inbox = ArgumentCaptor.forClass(CallbackWrite.class);
        verify(mapper).insertCallback(inbox.capture());
        assertThat(inbox.getValue().canonicalStatus()).isEqualTo("paid");
        assertThat(inbox.getValue().resultVersion()).isEqualTo(1L);
        assertThat(inbox.getValue().walletAfter()).isEqualByComparingTo("8.000000");
        ArgumentCaptor<CommerceAcceptanceSandboxMapper.SandboxAuditWrite> auditWrite =
                ArgumentCaptor.forClass(CommerceAcceptanceSandboxMapper.SandboxAuditWrite.class);
        verify(mapper).insertAudit(auditWrite.capture());
        assertThat(auditWrite.getValue().runId()).isEqualTo(RUN_ID);
        assertThat(auditWrite.getValue().event()).isEqualTo("PAYMENT_SUCCEEDED");
        assertThat(auditWrite.getValue().reason()).isEqualTo("acceptance sandbox callback");
        assertThat(auditWrite.getValue().canonicalStatus()).isEqualTo("paid");
    }

    @Test
    void paymentLedgerPreservesAnUnrelatedSandboxWithdrawalReservation() {
        SandboxOrder sandbox = sandbox("ORD-SBX-RESERVED", 0L, "PENDING_PAYMENT", false, false);
        when(mapper.lockSandboxOrder(RUN_ID, "ORD-SBX-RESERVED")).thenReturn(sandbox);
        when(mapper.findCallback(RUN_ID, "evt-payment-reserved")).thenReturn(null);
        when(funds.insertWalletIfAbsent(RUN_ID, 41L)).thenReturn(1);
        when(funds.lockWallet(RUN_ID, 41L)).thenReturn(new WalletRow(41L, money("20"), money("7"), 3L));
        when(funds.reserveWallet(RUN_ID, 41L, money("12"), 3L)).thenReturn(1);
        when(funds.consumeReservedWallet(RUN_ID, 41L, money("12"), 4L)).thenReturn(1);
        when(mapper.transitionSandboxOrder(RUN_ID, "ORD-SBX-RESERVED", 0L, "PENDING_PAYMENT", "PAID", true, false)).thenReturn(1);
        when(funds.insertLedger(eq(RUN_ID), any())).thenReturn(1);
        when(mapper.insertCallback(any())).thenReturn(1);

        service.applyCallback("ORD-SBX-RESERVED", "evt-payment-reserved", "PAYMENT_SUCCEEDED", 0L);

        ArgumentCaptor<LedgerWrite> ledger = ArgumentCaptor.forClass(LedgerWrite.class);
        verify(funds).insertLedger(eq(RUN_ID), ledger.capture());
        assertThat(ledger.getValue().reservedAfter()).isEqualByComparingTo("7.000000");
    }

    @Test
    void refundExactlyReversesSandboxDebitAndReleasesOnlySandboxInventoryOnce() {
        SandboxOrder sandbox = sandbox("ORD-SBX-2", 2L, "PAID", true, false);
        when(mapper.lockSandboxOrder(RUN_ID, "ORD-SBX-2")).thenReturn(sandbox);
        when(mapper.findCallback(RUN_ID, "evt-refund-ok")).thenReturn(null);
        when(funds.lockWallet(RUN_ID, 41L)).thenReturn(new WalletRow(41L, money("8"), money("0"), 4L));
        when(funds.creditWallet(RUN_ID, 41L, money("12"), 4L)).thenReturn(1);
        when(mapper.lockInventoryForOrder(RUN_ID, "ORD-SBX-2")).thenReturn(inventory("ORD-SBX-2", 4L, 0));
        when(mapper.releaseInventory(RUN_ID, "ORD-SBX-2", 4L)).thenReturn(1);
        when(mapper.lockSandboxCatalogProductForReturn(RUN_ID, 7L)).thenReturn(new CommerceAcceptanceSandboxMapper.SandboxCatalogProduct(
                7L, "SKU-7", "Sandbox", "Pro", money("12"), 0, 1, "gpu", 1,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, "", null, null, 9L, null));
        when(mapper.returnSandboxCatalogStock(RUN_ID, 7L, 9L, 1)).thenReturn(1);
        when(mapper.transitionSandboxOrder(RUN_ID, "ORD-SBX-2", 2L, "PAID", "REFUNDED", false, true)).thenReturn(1);
        when(funds.insertLedger(eq(RUN_ID), any())).thenReturn(1);
        when(mapper.insertCallback(any())).thenReturn(1);

        var result = service.applyCallback("ORD-SBX-2", "evt-refund-ok", "REFUNDED", 2L);

        assertThat(result.canonicalStatus()).isEqualTo("refunded");
        assertThat(result.walletAfter()).isEqualByComparingTo("20.000000");
        verify(funds).creditWallet(RUN_ID, 41L, money("12"), 4L);
        verify(mapper).releaseInventory(RUN_ID, "ORD-SBX-2", 4L);
        verify(mapper).returnSandboxCatalogStock(RUN_ID, 7L, 9L, 1);
        ArgumentCaptor<LedgerWrite> ledger = ArgumentCaptor.forClass(LedgerWrite.class);
        verify(funds).insertLedger(eq(RUN_ID), ledger.capture());
        assertThat(ledger.getValue().entryRole()).isEqualTo("COMMERCE_REFUND_CREDIT");
        assertThat(ledger.getValue().direction()).isEqualTo("IN");
        assertThat(ledger.getValue().availableAfter()).isEqualByComparingTo("20.000000");
    }

    @Test
    void staleVersionAndCrossOrderReplayFailClosedWithoutAnyWalletMutation() {
        when(mapper.lockSandboxOrder(RUN_ID, "ORD-SBX-3")).thenReturn(sandbox("ORD-SBX-3", 1L, "PENDING_PAYMENT", false, false));
        when(mapper.findCallback(RUN_ID, "evt-stale")).thenReturn(null);

        assertThatThrownBy(() -> service.applyCallback("ORD-SBX-3", "evt-stale", "PAYMENT_SUCCEEDED", 0L))
                .isInstanceOf(BizException.class).hasMessage("COMMERCE_SANDBOX_ORDER_VERSION_CONFLICT");
        verify(funds, never()).lockWallet(any(), any());
        verify(funds, never()).reserveWallet(any(), any(), any(), any());
    }

    @Test
    void sameEventReplayReturnsThePersistedFirstResponseWithoutReadingCurrentSandboxState() {
        when(mapper.findCallback(RUN_ID, "evt-replayed")).thenReturn(
                new CommerceAcceptanceSandboxMapper.Callback("evt-replayed", "ORD-SBX-4", "PAYMENT_SUCCEEDED", 0L,
                        hash("ORD-SBX-4|PAYMENT_SUCCEEDED|0"), "paid", 1L, money("8")));

        var result = service.applyCallback("ORD-SBX-4", "evt-replayed", "PAYMENT_SUCCEEDED", 0L);

        assertThat(result.canonicalStatus()).isEqualTo("paid");
        assertThat(result.version()).isEqualTo(1L);
        assertThat(result.walletAfter()).isEqualByComparingTo("8.000000");
        verify(mapper, never()).lockSandboxOrder(any(), any());
        verify(funds, never()).lockWallet(any(), any());
        verify(funds, never()).insertLedger(any(), any());
    }

    @Test
    void callbackThatWaitedForTheOrderLockUsesCurrentInboxReplayBeforeVersionValidation() {
        when(mapper.findCallback(RUN_ID, "evt-waited")).thenReturn(null);
        // This is the state observed after the first delivery committed. Without
        // the current inbox read the stale expectedVersion would become a 409.
        when(mapper.lockSandboxOrder(RUN_ID, "ORD-SBX-WAITED"))
                .thenReturn(sandbox("ORD-SBX-WAITED", 1L, "PAID", true, false));
        when(mapper.lockCurrentCallback(RUN_ID, "evt-waited")).thenReturn(
                new CommerceAcceptanceSandboxMapper.Callback("evt-waited", "ORD-SBX-WAITED", "PAYMENT_SUCCEEDED", 0L,
                        hash("ORD-SBX-WAITED|PAYMENT_SUCCEEDED|0"), "paid", 1L, money("8")));

        var result = service.applyCallback("ORD-SBX-WAITED", "evt-waited", "PAYMENT_SUCCEEDED", 0L);

        assertThat(result.canonicalStatus()).isEqualTo("paid");
        assertThat(result.version()).isEqualTo(1L);
        var sequence = org.mockito.Mockito.inOrder(mapper);
        sequence.verify(mapper).findCallback(RUN_ID, "evt-waited");
        sequence.verify(mapper).lockSandboxOrder(RUN_ID, "ORD-SBX-WAITED");
        sequence.verify(mapper).lockCurrentCallback(RUN_ID, "evt-waited");
        verify(mapper, never()).transitionSandboxOrder(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean());
        verify(funds, never()).lockWallet(any(), any());
        verify(funds, never()).insertLedger(any(), any());
    }

    @Test
    void arbitraryProductionOrderNumberWithoutAControlledSandboxMirrorIsRejectedWithoutFundsOrInventoryMutation() {
        when(mapper.lockSandboxOrder(RUN_ID, "ORD-PRODUCTION-ONLY")).thenReturn(null);
        when(mapper.findCallback(RUN_ID, "evt-production-order")).thenReturn(null);

        assertThatThrownBy(() -> service.applyCallback("ORD-PRODUCTION-ONLY", "evt-production-order", "PAYMENT_SUCCEEDED", 0L))
                .isInstanceOf(BizException.class).hasMessage("COMMERCE_SANDBOX_ORDER_NOT_FOUND");
        verify(funds, never()).lockWallet(any(), any());
        verify(funds, never()).insertLedger(any(), any());
        verify(mapper, never()).releaseInventory(any(), any(), any());
    }

    @Test
    void callbackNeverAdmitsOrMirrorsAnUncreatedCanonicalOrder() {
        when(mapper.lockSandboxOrder(RUN_ID, "ORD-SBX-MIRROR")).thenReturn(null);
        when(mapper.findCallback(RUN_ID, "evt-mirror")).thenReturn(null);

        assertThatThrownBy(() -> service.applyCallback("ORD-SBX-MIRROR", "evt-mirror", "PAYMENT_SUCCEEDED", 0L))
                .isInstanceOf(BizException.class).hasMessage("COMMERCE_SANDBOX_ORDER_NOT_FOUND");

        verify(mapper, never()).insertSandboxOrder(any());
        verify(mapper, never()).insertInventory(any());
        verify(funds, never()).lockWallet(any(), any());
    }

    @Test
    void disabledOrMixedFundsProfileFailsBeforeReadingOrMutatingAnyCommerceFact() {
        when(fundsGuard.isLocalSandboxEnabled()).thenReturn(false);

        assertThatThrownBy(() -> service.applyCallback("ORD-SBX-5", "evt-disabled", "PAYMENT_SUCCEEDED", 0L))
                .isInstanceOf(BizException.class).hasMessage("COMMERCE_SANDBOX_DISABLED");
        verify(mapper, never()).findCallback(any(), any());
        verify(funds, never()).lockWallet(any(), any());
    }

    @Test
    void zeroAmountSandboxOrderReachesTerminalStateWithoutFundsCasOrLedger() {
        SandboxOrder zero = new SandboxOrder("ORD-SBX-ZERO", 41L, 7L, 1, money("0"), 0L,
                "PENDING_PAYMENT", false, false);
        when(mapper.findCallback(RUN_ID, "evt-zero-payment")).thenReturn(null);
        when(mapper.lockSandboxOrder(RUN_ID, "ORD-SBX-ZERO")).thenReturn(zero);
        when(mapper.insertCallback(any())).thenReturn(1);
        when(mapper.transitionSandboxOrder(RUN_ID, "ORD-SBX-ZERO", 0L, "PENDING_PAYMENT", "PAID", false, false)).thenReturn(1);

        var result = service.applyCallback("ORD-SBX-ZERO", "evt-zero-payment", "PAYMENT_SUCCEEDED", 0L);

        assertThat(result.canonicalStatus()).isEqualTo("paid");
        assertThat(result.walletAfter()).isNull();
        verify(funds, never()).insertWalletIfAbsent(any(), any());
        verify(funds, never()).reserveWallet(any(), any(), any(), any());
        verify(funds, never()).insertLedger(any(), any());
    }

    private static SandboxOrder sandbox(String orderNo, long version, String state, boolean debited, boolean stockReturned) {
        return new SandboxOrder(orderNo, 41L, 7L, 1, money("12"), version, state, debited, stockReturned);
    }

    private static InventoryRow inventory(String orderNo, long version, int releasedQuantity) {
        return new InventoryRow(orderNo, 7L, "SKU-7", money("12"), 1, releasedQuantity, version);
    }

    private static BigDecimal money(String value) { return new BigDecimal(value).setScale(6); }

    private static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
