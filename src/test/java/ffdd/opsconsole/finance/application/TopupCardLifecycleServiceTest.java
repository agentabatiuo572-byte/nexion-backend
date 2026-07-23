package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.domain.TopupAdmissionReceipt;
import ffdd.opsconsole.finance.domain.TopupChargebackSource;
import ffdd.opsconsole.finance.domain.TopupFeeBufferSnapshot;
import ffdd.opsconsole.finance.domain.TopupProviderStatementReceipt;
import ffdd.opsconsole.finance.domain.TopupRiskLockSnapshot;
import ffdd.opsconsole.finance.domain.TopupWalletSnapshot;
import ffdd.opsconsole.finance.dto.TopupCardAdmissionRequest;
import ffdd.opsconsole.finance.dto.TopupCardChargebackRequest;
import ffdd.opsconsole.finance.dto.TopupCardFailureRequest;
import ffdd.opsconsole.finance.dto.TopupCardSettlementRequest;
import ffdd.opsconsole.finance.dto.TopupProviderStatementRequest;
import ffdd.opsconsole.finance.mapper.D1FinanceClosureMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TopupCardLifecycleServiceTest {
    private final D1FinanceClosureMapper mapper = mock(D1FinanceClosureMapper.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final TreasuryLedgerRepository treasury = mock(TreasuryLedgerRepository.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final PlatformConfigFacade config = mock(PlatformConfigFacade.class);
    private TopupCardLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new TopupCardLifecycleService(mapper, audit, treasury, outbox, config);
        Map<String, String> defaults = Map.of(
                "finance.topup.channel.card.enabled", "true",
                "finance.topup.psp.primary", "Checkout.com",
                "finance.topup.channel.card.min_amount", "10",
                "finance.topup.channel.card.fee", "3.5",
                "finance.topup.card.threeDsThreshold", "50");
        when(config.activeValue(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(defaults.get(invocation.getArgument(0))));
        when(mapper.insertAdmissionReceipt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any())).thenReturn(1);
    }

    @Test
    void admissionUsesServerCanonicalFeeMinimumProviderAndThreeDsPolicy() {
        var result = service.admit(admission(new BigDecimal("100"), "AUTHENTICATED"));

        assertThat(result.decision()).isEqualTo("ALLOWED");
        assertThat(result.feeRatePct()).isEqualByComparingTo("3.500000");
        assertThat(result.feeAmountUsdt()).isEqualByComparingTo("3.500000");
        verify(audit).recordRequired(any(AuditLogWriteRequest.class));
        verify(outbox).publish(eq("WALLET"), eq("ord-100"), eq("wallet.topup_initiated"), any());

        assertThatThrownBy(() -> service.admit(admission(new BigDecimal("9"), "AUTHENTICATED")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("CARD_ADMISSION_BELOW_MIN_AMOUNT");
        assertThatThrownBy(() -> service.admit(admission(new BigDecimal("50"), "EXEMPTED")))
                .isInstanceOf(IllegalStateException.class).hasMessage("CARD_3DS_AUTHENTICATION_REQUIRED");
    }

    @Test
    void admissionAndSettlementBothFailClosedOnActiveRiskLock() {
        when(mapper.selectRiskLockForUpdate("BIN", "424242")).thenReturn(activeBinLock());

        var denied = service.admit(admission(new BigDecimal("20"), "EXEMPTED"));

        assertThat(denied.decision()).isEqualTo("DENIED");
        assertThat(denied.reason()).contains("BIN");
        when(mapper.selectAdmissionForUpdate("adm-100", "ord-100")).thenReturn(authorizedAdmission());
        assertThatThrownBy(() -> service.settle(settlement()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("RISK_LOCK_ACTIVE");
        verify(mapper, never()).updateWallet(any(), any(), any(), any());
    }

    @Test
    void settlementPostsExactD4WalletCumulativeFeeBufferD3AndCanonicalOutboxAtomically() {
        when(mapper.selectAdmissionForUpdate("adm-100", "ord-100")).thenReturn(authorizedAdmission());
        when(mapper.insertSettlementReceipt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(mapper.consumeAdmission("adm-100", "set-100")).thenReturn(1);
        when(mapper.selectWalletForUpdate(42L)).thenReturn(
                new TopupWalletSnapshot(42L, new BigDecimal("10"), new BigDecimal("20"), 7L));
        when(mapper.selectFeeBufferForUpdate()).thenReturn(new TopupFeeBufferSnapshot(new BigDecimal("5"), 3L));
        when(mapper.updateWallet(any(), any(), any(), any())).thenReturn(1);
        when(mapper.updateFeeBuffer(any(), any())).thenReturn(1);
        when(mapper.insertSettledPayment(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(mapper.bindPaymentWalletLedger("pay-100")).thenReturn(1);
        when(mapper.completeSettlement(any(), any(), any(), any())).thenReturn(1);

        var result = service.settle(settlement());

        assertThat(result.walletBalanceAfter()).isEqualByComparingTo("110.000000");
        assertThat(result.cumulativeDepositAfter()).isEqualByComparingTo("120.000000");
        assertThat(result.feeBufferBalanceAfter()).isEqualByComparingTo("8.500000");
        verify(mapper).insertCardTopupWalletLedger(
                eq(42L), eq("pay-100"), eq(new BigDecimal("100.000000")),
                eq(new BigDecimal("110.000000")), anyString());
        verify(mapper).insertFeeBufferCredit(any(), eq("pay-100"), eq(new BigDecimal("3.500000")),
                eq(new BigDecimal("8.500000")), any(), any(), eq("set-100"));
        verify(treasury).recordTopupReserve("pay-100", new BigDecimal("100.000000"), "set-100");
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outbox).publish(eq("WALLET"), eq("pay-100"), eq("wallet.topup_confirmed"), payload.capture());
        assertThat(payload.getValue()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> eventPayload = (Map<String, Object>) payload.getValue();
        assertThat(eventPayload)
                .containsEntry("transaction_id", "pay-100")
                .containsEntry("user_id", 42L)
                .containsEntry("topup_id", "pay-100")
                .containsEntry("channel", "Card")
                .containsEntry("psp", "Checkout.com");
        verify(audit).recordRequired(any(AuditLogWriteRequest.class));
    }

    @Test
    void sameOrderWithDifferentAdmissionEventAndChangedPayloadIsRejected() {
        TopupAdmissionReceipt existing = authorizedAdmission();
        when(mapper.selectAdmissionForUpdate("adm-other", "ord-100")).thenReturn(existing);
        TopupCardAdmissionRequest replay = new TopupCardAdmissionRequest(
                "adm-other", "ord-100", 42L, "Checkout.com", new BigDecimal("100"),
                "AUTHENTICATED", "424242", "203.0.113.10", "device-100");

        assertThatThrownBy(() -> service.admit(replay))
                .isInstanceOf(IllegalStateException.class).hasMessage("ADMISSION_EVENT_CONFLICT");
    }

    @Test
    void requiredAuditFailurePropagatesSoTransactionalCallerRollsBackMoneyWrites() {
        when(mapper.selectAdmissionForUpdate("adm-100", "ord-100")).thenReturn(authorizedAdmission());
        when(mapper.insertSettlementReceipt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(mapper.consumeAdmission(any(), any())).thenReturn(1);
        when(mapper.selectWalletForUpdate(42L)).thenReturn(new TopupWalletSnapshot(42L, BigDecimal.ZERO, BigDecimal.ZERO, 1L));
        when(mapper.selectFeeBufferForUpdate()).thenReturn(new TopupFeeBufferSnapshot(BigDecimal.ZERO, 1L));
        when(mapper.updateWallet(any(), any(), any(), any())).thenReturn(1);
        when(mapper.updateFeeBuffer(any(), any())).thenReturn(1);
        when(mapper.insertSettledPayment(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(mapper.bindPaymentWalletLedger(any())).thenReturn(1);
        when(mapper.completeSettlement(any(), any(), any(), any())).thenReturn(1);
        doThrow(new IllegalStateException("AUDIT_DOWN")).when(audit).recordRequired(any());

        assertThatThrownBy(() -> service.settle(settlement()))
                .isInstanceOf(IllegalStateException.class).hasMessage("AUDIT_DOWN");
    }

    @Test
    void providerStatementIngestionIsIndependentIdempotentAndConflictAware() {
        TopupProviderStatementRequest request = statement();
        when(mapper.insertProviderStatement(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        var inserted = service.ingestProviderStatement(request);
        assertThat(inserted.replay()).isFalse();
        verify(mapper, never()).updateWallet(any(), any(), any(), any());

        when(mapper.insertProviderStatement(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(0);
        when(mapper.selectProviderStatementForUpdate(any(), any(), any(), any())).thenReturn(
                new TopupProviderStatementReceipt("ing-100", "stmt-100", "Checkout.com", "psp-ref-100",
                        "different-hash", "SETTLED"));
        assertThatThrownBy(() -> service.ingestProviderStatement(request))
                .isInstanceOf(IllegalStateException.class).hasMessage("PROVIDER_STATEMENT_EVENT_CONFLICT");
    }

    @Test
    void signedFailureConsumesAdmissionAndPersistsRiskDimensionsWithoutMoneyWrites() {
        when(mapper.selectAdmissionForUpdate("adm-100", "ord-100")).thenReturn(authorizedAdmission());
        when(mapper.insertFailureReceipt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(mapper.failAdmission("adm-100", "fail-100")).thenReturn(1);
        when(mapper.insertFailedPayment(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        var result = service.recordFailure(new TopupCardFailureRequest(
                "fail-100", "adm-100", "pay-fail-100", "ord-100", 42L, "Checkout.com",
                "psp-fail-100", "DECLINED", "issuer declined payment", utcNow()));

        assertThat(result.status()).isEqualTo("DECLINED");
        verify(mapper).insertFailedPayment(
                eq("fail-100"), eq("adm-100"), eq("pay-fail-100"), eq("ord-100"), eq(42L),
                eq("Checkout.com"), eq("psp-fail-100"), eq("DECLINED"),
                eq("issuer declined payment"), any());
        verify(mapper, never()).updateWallet(any(), any(), any(), any());
        verify(treasury, never()).recordTopupReserve(any(), any(), any());
        verify(outbox, never()).publish(any(), any(), any(), any());
        verify(audit).recordRequired(any(AuditLogWriteRequest.class));
    }

    @Test
    void signedChargebackRequiresExactConfirmedPaymentAndOnlyMovesItIntoReviewQueue() {
        when(mapper.selectChargebackSourceForUpdate("pay-100", "Checkout.com", "psp-pay-100"))
                .thenReturn(new TopupChargebackSource(
                        "pay-100", "ord-100", 42L, "Checkout.com", "psp-pay-100",
                        new BigDecimal("100.000000"), "CONFIRMED", null));
        when(mapper.insertChargebackEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(mapper.applyChargebackStatus(any(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        var result = service.recordChargeback(new TopupCardChargebackRequest(
                "cb-event-100", "pay-100", "ord-100", 42L, "Checkout.com", "psp-pay-100",
                new BigDecimal("100"), "DISPUTED", "issuer opened a card dispute",
                "psp://dispute/100", utcNow()));

        assertThat(result.status()).isEqualTo("DISPUTED");
        verify(mapper).applyChargebackStatus(
                "cb-event-100", "pay-100", "Checkout.com", "psp-pay-100", "CONFIRMED", "DISPUTED",
                "issuer opened a card dispute");
        verify(mapper, never()).updateWallet(any(), any(), any(), any());
        verify(treasury, never()).reverseTopupReserve(any(), any(), any());
        verify(audit).recordRequired(any(AuditLogWriteRequest.class));
    }

    @Test
    void signedChargebackRejectsBackwardOrStaleProviderEventsBeforeAnyWrite() {
        LocalDateTime latest = utcNow();
        when(mapper.selectChargebackSourceForUpdate("pay-100", "Checkout.com", "psp-pay-100"))
                .thenReturn(new TopupChargebackSource(
                        "pay-100", "ord-100", 42L, "Checkout.com", "psp-pay-100",
                        new BigDecimal("100.000000"), "CHARGEBACK_REVIEW", latest));

        assertThatThrownBy(() -> service.recordChargeback(new TopupCardChargebackRequest(
                "cb-event-backward", "pay-100", "ord-100", 42L, "Checkout.com", "psp-pay-100",
                new BigDecimal("100"), "DISPUTED", "late dispute replay",
                "psp://dispute/backward", latest.plusSeconds(1))))
                .isInstanceOf(IllegalStateException.class).hasMessage("CHARGEBACK_EVENT_OUT_OF_ORDER");

        assertThatThrownBy(() -> service.recordChargeback(new TopupCardChargebackRequest(
                "cb-event-stale", "pay-100", "ord-100", 42L, "Checkout.com", "psp-pay-100",
                new BigDecimal("100"), "CHARGEBACK", "stale final event",
                "psp://dispute/stale", latest.minusSeconds(1))))
                .isInstanceOf(IllegalStateException.class).hasMessage("CHARGEBACK_EVENT_OUT_OF_ORDER");

        verify(mapper, never()).insertChargebackEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(mapper, never()).applyChargebackStatus(any(), any(), any(), any(), any(), any(), any());
        verify(audit, never()).recordRequired(any());
    }

    @Test
    void independentChainObservationAcceptsOnlyChannelMatchedNodeProvider() {
        TopupProviderStatementRequest chain = new TopupProviderStatementRequest(
                "ing-chain-100", "stmt-chain-100", "TRON-NODE", "USDT-TRC20",
                "trx-hash-100", 42L, new BigDecimal("20"), "CONFIRMED",
                "node://tron/100", utcNow());
        when(mapper.insertProviderStatement(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        assertThat(service.ingestProviderStatement(chain).status()).isEqualTo("CONFIRMED");
        assertThatThrownBy(() -> service.ingestProviderStatement(new TopupProviderStatementRequest(
                "ing-chain-101", "stmt-chain-101", "BITCOIN-NODE", "USDT-TRC20",
                "trx-hash-101", 42L, new BigDecimal("20"), "CONFIRMED",
                "node://btc/101", utcNow())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("PROVIDER_STATEMENT_PROVIDER_CHANNEL_INVALID");
    }

    private TopupCardAdmissionRequest admission(BigDecimal amount, String threeDsStatus) {
        return new TopupCardAdmissionRequest(
                "adm-100", "ord-100", 42L, "Checkout.com", amount, threeDsStatus,
                "424242", "203.0.113.10", "device-100");
    }

    private TopupAdmissionReceipt authorizedAdmission() {
        return new TopupAdmissionReceipt(
                "adm-100", "request-hash", "ord-100", 42L, "Checkout.com",
                new BigDecimal("100.000000"), new BigDecimal("3.500000"), new BigDecimal("3.500000"),
                "AUTHENTICATED", "424242", "203.0.113.10", "device-100", "ALLOWED",
                "RISK_CHECK_PASSED", utcNow().plusMinutes(5), null, null);
    }

    private TopupCardSettlementRequest settlement() {
        return new TopupCardSettlementRequest(
                "set-100", "adm-100", "pay-100", "ord-100", 42L, "Checkout.com",
                "psp-pay-100", new BigDecimal("100"), new BigDecimal("3.5"), new BigDecimal("3.5"),
                utcNow());
    }

    private TopupProviderStatementRequest statement() {
        return new TopupProviderStatementRequest(
                "ing-100", "stmt-100", "Checkout.com", "card", "psp-ref-100", 42L,
                new BigDecimal("100"), "SETTLED", "s3://statement/100", utcNow());
    }

    private TopupRiskLockSnapshot activeBinLock() {
        return new TopupRiskLockSnapshot(
                "BIN", "424242", "ACTIVE", "MANUAL", "fraud burst", utcNow().plusHours(1), true);
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
    }
}
