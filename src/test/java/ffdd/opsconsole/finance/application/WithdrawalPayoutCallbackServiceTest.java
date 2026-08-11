package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.cregis.CregisConstants;
import ffdd.opsconsole.finance.cregis.CregisProperties;
import ffdd.opsconsole.finance.cregis.CregisSigner;
import ffdd.opsconsole.finance.cregis.CregisGateway;
import ffdd.opsconsole.finance.cregis.CregisGatewayRouter;
import ffdd.opsconsole.finance.mapper.WithdrawalPayoutMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WithdrawalPayoutCallbackServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-10T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void verifiedProviderSuccessCallbackDrivesTerminalFinalizer() {
        WithdrawalPayoutMapper mapper = mock(WithdrawalPayoutMapper.class);
        WithdrawalPayoutFinalizer finalizer = mock(WithdrawalPayoutFinalizer.class);
        CregisProperties properties = properties();
        CregisSigner signer = new CregisSigner();
        var row = row();
        when(mapper.payoutByProviderKey("WD-CALLBACK-1")).thenReturn(row);
        Map<String, Object> callback = callback();
        callback.put("sign", signer.sign(properties.getApiKey(), callback));

        var result = service(mapper, properties, signer, finalizer, row, 6, "0x" + "a".repeat(64))
                .receive(callback);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("status", "CHAIN_CONFIRMATION_PENDING")
                .containsEntry("callbackVerified", true).containsEntry("source", "provider");
        verify(finalizer, never()).terminal(any(), anyLong(), anyString(), anyString(), anyString(),
                anyString(), any(), any());
    }

    @Test
    void forgedCallbackCannotMutateOrderWalletLedgerOrAudit() {
        WithdrawalPayoutMapper mapper = mock(WithdrawalPayoutMapper.class);
        WithdrawalPayoutFinalizer finalizer = mock(WithdrawalPayoutFinalizer.class);
        var row = row();
        when(mapper.payoutByProviderKey("WD-CALLBACK-1")).thenReturn(row);
        Map<String, Object> callback = callback();
        callback.put("sign", "0".repeat(64));

        CregisGatewayRouter router = mock(CregisGatewayRouter.class);
        var result = new WithdrawalPayoutCallbackService(
                mapper, properties(), new CregisSigner(), finalizer, router, CLOCK).receive(callback);

        assertThat(result.getCode()).isEqualTo(401);
        verify(finalizer, never()).terminal(eq(row), eq(9001L), eq("provider"),
                anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @ParameterizedTest
    @CsvSource({
            "0,PROVIDER_PROGRESS", "1,PROVIDER_PROGRESS", "2,FAILED", "3,FAILED",
            "4,FAILED", "5,PROVIDER_PROGRESS", "6,CHAIN_CONFIRMATION_PENDING", "7,TX_ORPHANED"
    })
    void providerStatusesZeroThroughSevenOnlyTerminalizeDocumentedTerminalStates(
            int providerStatus, String expectedStatus) {
        WithdrawalPayoutMapper mapper = mock(WithdrawalPayoutMapper.class);
        WithdrawalPayoutFinalizer finalizer = mock(WithdrawalPayoutFinalizer.class);
        CregisProperties properties = properties();
        CregisSigner signer = new CregisSigner();
        var row = row();
        when(mapper.payoutByProviderKey("WD-CALLBACK-1")).thenReturn(row);
        when(finalizer.terminal(eq(row), eq(9001L), eq("provider"), anyString(), anyString(),
                anyString(), anyString(), anyString())).thenReturn(true);
        Map<String, Object> callback = callback();
        callback.put("status", providerStatus);
        if (providerStatus != 6) callback.remove("txid");
        callback.put("sign", signer.sign(properties.getApiKey(), callback));

        String txid = providerStatus == 6 ? "0x" + "a".repeat(64) : null;
        var result = service(mapper, properties, signer, finalizer, row, providerStatus, txid)
                .receive(callback);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("status", expectedStatus);
        if ("PROVIDER_PROGRESS".equals(expectedStatus)) {
            verify(finalizer, never()).terminal(eq(row), eq(9001L), eq("provider"),
                    anyString(), anyString(), anyString(), anyString(), anyString());
        }
    }

    @Test
    void lateNonTerminalCallbackAfterConfirmedCannotRefundOrChangeTerminalState() {
        WithdrawalPayoutMapper mapper = mock(WithdrawalPayoutMapper.class);
        WithdrawalPayoutFinalizer finalizer = mock(WithdrawalPayoutFinalizer.class);
        CregisProperties properties = properties();
        CregisSigner signer = new CregisSigner();
        var confirmed = new WithdrawalPayoutMapper.PayoutRow(
                "WD-CALLBACK-1", 42L, "USDT-BEP20", "0x1111111111111111111111111111111111111111",
                new BigDecimal("10.000000"), new BigDecimal("9.000000"), BigDecimal.ZERO,
                "CONFIRMED", LocalDateTime.now().plusHours(1), 9001L, "WD-CALLBACK-1", "provider", 1);
        when(mapper.payoutByProviderKey("WD-CALLBACK-1")).thenReturn(confirmed);
        Map<String, Object> callback = callback();
        callback.put("status", 1);
        callback.remove("txid");
        callback.put("sign", signer.sign(properties.getApiKey(), callback));

        var result = service(mapper, properties, signer, finalizer, confirmed, 1, null)
                .receive(callback);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("status", "PROVIDER_PROGRESS");
        verify(finalizer, never()).terminal(eq(confirmed), eq(9001L), eq("provider"),
                anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void outOfOrderFailureAfterObservedProviderSuccessIsHeldAndNeverRefunded() {
        WithdrawalPayoutMapper mapper = mock(WithdrawalPayoutMapper.class);
        WithdrawalPayoutFinalizer finalizer = mock(WithdrawalPayoutFinalizer.class);
        CregisProperties properties = properties();
        CregisSigner signer = new CregisSigner();
        var row = row();
        when(mapper.payoutByProviderKey("WD-CALLBACK-1")).thenReturn(row);
        when(mapper.hasProviderSuccessEvidence(row.withdrawalNo())).thenReturn(true);
        Map<String, Object> callback = callback();
        callback.put("status", 2);
        callback.remove("txid");
        callback.put("sign", signer.sign(properties.getApiKey(), callback));

        var result = service(mapper, properties, signer, finalizer, row, 2, null).receive(callback);

        assertThat(result.getData()).containsEntry("status", "TX_ORPHANED");
        verify(finalizer).holdAmbiguousCallback(eq(row), eq(9001L), anyString(), anyString(), eq(2), eq(null));
        verify(finalizer, never()).terminal(any(), anyLong(), anyString(), anyString(), anyString(),
                anyString(), any(), any());
    }

    private CregisProperties properties() {
        CregisProperties value = new CregisProperties();
        value.setMode(CregisProperties.Mode.PROVIDER);
        value.setProjectId(101L);
        value.setApiKey("test-provider-callback-key");
        return value;
    }

    private WithdrawalPayoutCallbackService service(WithdrawalPayoutMapper mapper, CregisProperties properties,
                                                     CregisSigner signer, WithdrawalPayoutFinalizer finalizer,
                                                     WithdrawalPayoutMapper.PayoutRow row,
                                                     int providerStatus, String txid) {
        CregisGatewayRouter router = mock(CregisGatewayRouter.class);
        CregisGateway gateway = mock(CregisGateway.class);
        when(mapper.insertCallbackInbox(anyString(), eq(row.withdrawalNo()), eq(9001L),
                eq(providerStatus), any(), anyString(), any())).thenReturn(1);
        when(mapper.claimCallbackInbox(anyString(), any(), any())).thenReturn(1);
        when(mapper.payout(row.withdrawalNo())).thenReturn(row);
        when(router.provider()).thenReturn(gateway);
        when(gateway.queryPayout(any())).thenReturn(new CregisGateway.PayoutOrder(
                9001L, CregisConstants.USDT_BEP20_CURRENCY, CregisConstants.BSC_CHAIN_ID,
                CregisConstants.USDT_BEP20_TOKEN_ID, row.targetAddress(), row.netReceive(),
                row.providerIdempotencyKey(), CregisGateway.PayoutStatus.fromProviderCode(providerStatus), txid));
        when(finalizer.providerProgress(eq(row), eq(9001L), anyString(), anyString(),
                eq(providerStatus), any())).thenReturn(true);
        when(finalizer.holdAmbiguousCallback(eq(row), eq(9001L), anyString(), anyString(),
                eq(providerStatus), any())).thenReturn(true);
        when(finalizer.terminal(eq(row), eq(9001L), eq("provider"), anyString(), anyString(),
                anyString(), any(), any())).thenReturn(true);
        return new WithdrawalPayoutCallbackService(mapper, properties, signer, finalizer, router, CLOCK);
    }

    private WithdrawalPayoutMapper.PayoutRow row() {
        return new WithdrawalPayoutMapper.PayoutRow(
                "WD-CALLBACK-1", 42L, "USDT-BEP20", "0x1111111111111111111111111111111111111111",
                new BigDecimal("10.000000"), new BigDecimal("9.000000"), BigDecimal.ZERO,
                "SENT", LocalDateTime.now().plusHours(1), 9001L, "WD-CALLBACK-1", "provider", 1);
    }

    private Map<String, Object> callback() {
        Map<String, Object> callback = new LinkedHashMap<>();
        callback.put("pid", 101L);
        callback.put("cid", 9001L);
        callback.put("chain_id", CregisConstants.BSC_CHAIN_ID);
        callback.put("token_id", CregisConstants.USDT_BEP20_TOKEN_ID);
        callback.put("currency", CregisConstants.USDT_BEP20_CURRENCY);
        callback.put("address", "0x1111111111111111111111111111111111111111");
        callback.put("amount", "9.000000");
        callback.put("third_party_id", "WD-CALLBACK-1");
        callback.put("status", 6);
        callback.put("txid", "0x" + "a".repeat(64));
        callback.put("nonce", "abc123");
        callback.put("timestamp", CLOCK.millis());
        return callback;
    }
}
