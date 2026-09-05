package ffdd.opsconsole.finance.hdpay;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class HdPayCallbackRecoverySchedulerTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-02T04:00:00Z"), ZoneOffset.UTC);

    private final HdPayGateway gateway = mock(HdPayGateway.class);
    private final HdPayCallbackSettlementService settlement = mock(HdPayCallbackSettlementService.class);

    @Test
    void staleDurablePaidCallbackIsQueriedAndSettledWithoutAnotherProviderDelivery() {
        HdPayProperties properties = providerProperties();
        var fact = new HdPayCallbackSettlementService.PaidCallbackFact(
                "hash-1", "VQR-1", "P-1", 3, new BigDecimal("100000"));
        var query = new HdPayGateway.PayOrder(
                "VQR-1", "P-1", 3, new BigDecimal("100000"), "BANKQR", "");
        when(settlement.listRecoverablePaidCallbacks(any(), eq(20))).thenReturn(List.of(fact));
        when(settlement.claimStoredCallbackForRetry(eq("hash-1"), any())).thenReturn("claim-1");
        when(gateway.queryPayOrder("VQR-1")).thenReturn(query);

        new HdPayCallbackRecoveryScheduler(properties, gateway, settlement, CLOCK).recover();

        verify(settlement).settleConfirmed(fact, "claim-1", query);
    }

    @Test
    void disabledProviderNeverScansOrQueries() {
        new HdPayCallbackRecoveryScheduler(
                new HdPayProperties(), gateway, settlement, CLOCK).recover();

        verify(settlement, never()).listRecoverablePaidCallbacks(any(), anyInt());
        verify(gateway, never()).queryPayOrder(any());
    }

    private HdPayProperties providerProperties() {
        HdPayProperties properties = new HdPayProperties();
        properties.setMode(HdPayProperties.Mode.PROVIDER);
        properties.setBaseUrl("https://api.hdpayadmin.com/api/order");
        properties.setCallbackBaseUrl("https://payments.example.com");
        properties.setCallbackHosts(java.util.List.of("payments.example.com"));
        properties.setMerchantId("1234567890123456789");
        properties.setMd5Key("0123456789abcdef0123456789abcdef");
        return properties;
    }
}
