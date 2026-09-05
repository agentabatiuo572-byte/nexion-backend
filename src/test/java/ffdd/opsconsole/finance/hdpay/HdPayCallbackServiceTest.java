package ffdd.opsconsole.finance.hdpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class HdPayCallbackServiceTest {
    private final HdPayGateway gateway = mock(HdPayGateway.class);
    private final HdPayCallbackVerifier verifier = mock(HdPayCallbackVerifier.class);
    private final HdPayCallbackSettlementService settlement = mock(HdPayCallbackSettlementService.class);
    private final HdPayCallbackService service = new HdPayCallbackService(gateway, verifier, settlement);

    @Test
    void completedCallbackMustBeConfirmedByProviderQueryBeforeSettlement() {
        JsonNode body = new ObjectMapper().createObjectNode();
        HdPayCallbackVerifier.VerifiedCallback callback = callback(3, "100.00");
        HdPayCallbackSettlementService.PaidCallbackFact fact = fact("100.00");
        HdPayGateway.PayOrder query = payOrder(3, "100.00");
        when(verifier.verify(body)).thenReturn(callback);
        when(settlement.claimForProviderQuery(callback)).thenReturn(new HdPayCallbackSettlementService.QueryClaim(
                HdPayCallbackSettlementService.ClaimDisposition.QUERY_PROVIDER, fact, "claim-1"));
        when(gateway.queryPayOrder("VQR-1")).thenReturn(query);
        when(settlement.settleConfirmed(fact, "claim-1", query)).thenReturn("success");

        assertThat(service.accept(body)).isEqualTo("success");

        verify(gateway).queryPayOrder("VQR-1");
        verify(settlement).settleConfirmed(fact, "claim-1", query);
    }

    @Test
    void nonPaidCallbackIsObservedButCannotEnterSettlement() {
        JsonNode body = new ObjectMapper().createObjectNode();
        HdPayCallbackVerifier.VerifiedCallback callback = callback(4, "100.00");
        when(verifier.verify(body)).thenReturn(callback);
        when(settlement.observe(callback)).thenReturn("success");

        assertThat(service.accept(body)).isEqualTo("success");

        verify(settlement).observe(callback);
        verify(gateway, never()).queryPayOrder("VQR-1");
        verify(settlement, never()).settleConfirmed(
                org.mockito.ArgumentMatchers.any(HdPayCallbackSettlementService.PaidCallbackFact.class),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(HdPayGateway.PayOrder.class));
    }

    @Test
    void providerQueryThatIsStillPendingFailsClosed() {
        JsonNode body = new ObjectMapper().createObjectNode();
        HdPayCallbackVerifier.VerifiedCallback callback = callback(3, "100.00");
        HdPayCallbackSettlementService.PaidCallbackFact fact = fact("100.00");
        when(verifier.verify(body)).thenReturn(callback);
        when(settlement.claimForProviderQuery(callback)).thenReturn(new HdPayCallbackSettlementService.QueryClaim(
                HdPayCallbackSettlementService.ClaimDisposition.QUERY_PROVIDER, fact, "claim-1"));
        when(gateway.queryPayOrder("VQR-1")).thenReturn(payOrder(1, "100.00"));

        assertThatThrownBy(() -> service.accept(body))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("HDPAY_CALLBACK_QUERY_NOT_PAID");

        verify(settlement, never()).settleConfirmed(
                org.mockito.ArgumentMatchers.any(HdPayCallbackSettlementService.PaidCallbackFact.class),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(HdPayGateway.PayOrder.class));
        verify(settlement).releaseProviderQueryClaim(
                fact, "claim-1", 1, "HDPAY_CALLBACK_QUERY_NOT_PAID");
    }

    @Test
    void providerIdentityOrAmountMismatchFailsClosed() {
        JsonNode body = new ObjectMapper().createObjectNode();
        HdPayCallbackVerifier.VerifiedCallback callback = callback(3, "100.00");
        HdPayCallbackSettlementService.PaidCallbackFact fact = fact("100.00");
        when(verifier.verify(body)).thenReturn(callback);
        when(settlement.claimForProviderQuery(callback)).thenReturn(new HdPayCallbackSettlementService.QueryClaim(
                HdPayCallbackSettlementService.ClaimDisposition.QUERY_PROVIDER, fact, "claim-1"));
        when(gateway.queryPayOrder("VQR-1")).thenReturn(new HdPayGateway.PayOrder(
                "VQR-1", "OTHER-PROVIDER-ORDER", 3,
                new BigDecimal("99.00"), "BANKQR", ""));

        assertThat(service.accept(body)).isEqualTo("success");

        verify(settlement, never()).settleConfirmed(
                org.mockito.ArgumentMatchers.any(HdPayCallbackSettlementService.PaidCallbackFact.class),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(HdPayGateway.PayOrder.class));
        verify(settlement).reviewProviderQueryClaim(
                fact, "claim-1", "HDPAY_CALLBACK_QUERY_MISMATCH");
    }

    @Test
    void exactTerminalReplayIsAcknowledgedWithoutAnotherProviderQuery() {
        JsonNode body = new ObjectMapper().createObjectNode();
        HdPayCallbackVerifier.VerifiedCallback callback = callback(3, "100.00");
        HdPayCallbackSettlementService.PaidCallbackFact fact = fact("100.00");
        when(verifier.verify(body)).thenReturn(callback);
        when(settlement.claimForProviderQuery(callback)).thenReturn(new HdPayCallbackSettlementService.QueryClaim(
                HdPayCallbackSettlementService.ClaimDisposition.ACKNOWLEDGED, fact, null));

        assertThat(service.accept(body)).isEqualTo("success");

        verify(gateway, never()).queryPayOrder("VQR-1");
    }

    @Test
    void concurrentReplayIsThrottledBeforeProviderQuery() {
        JsonNode body = new ObjectMapper().createObjectNode();
        HdPayCallbackVerifier.VerifiedCallback callback = callback(3, "100.00");
        HdPayCallbackSettlementService.PaidCallbackFact fact = fact("100.00");
        when(verifier.verify(body)).thenReturn(callback);
        when(settlement.claimForProviderQuery(callback)).thenReturn(new HdPayCallbackSettlementService.QueryClaim(
                HdPayCallbackSettlementService.ClaimDisposition.RETRY_LATER, fact, null));

        assertThatThrownBy(() -> service.accept(body))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("HDPAY_CALLBACK_QUERY_ALREADY_CLAIMED");

        verify(gateway, never()).queryPayOrder("VQR-1");
    }

    private HdPayCallbackVerifier.VerifiedCallback callback(int status, String amount) {
        return new HdPayCallbackVerifier.VerifiedCallback(
                "VQR-1", "P-1", status, new BigDecimal(amount), null, null, "abc");
    }

    private HdPayGateway.PayOrder payOrder(int status, String amount) {
        return new HdPayGateway.PayOrder(
                "VQR-1", "P-1", status, new BigDecimal(amount), "BANKQR", "");
    }

    private HdPayCallbackSettlementService.PaidCallbackFact fact(String amount) {
        return new HdPayCallbackSettlementService.PaidCallbackFact(
                "hash-1", "VQR-1", "P-1", 3, new BigDecimal(amount));
    }
}
