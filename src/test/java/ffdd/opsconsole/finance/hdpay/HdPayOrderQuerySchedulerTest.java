package ffdd.opsconsole.finance.hdpay;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HdPayOrderQuerySchedulerTest {
    private final HdPayProperties properties = mock(HdPayProperties.class);
    private final HdPayOrderMapper mapper = mock(HdPayOrderMapper.class);
    private final HdPayGateway gateway = mock(HdPayGateway.class);
    private final HdPayCallbackSettlementService settlement = mock(HdPayCallbackSettlementService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC);
    private final HdPayOrderQueryScheduler scheduler = new HdPayOrderQueryScheduler(
            properties, mapper, gateway, settlement, clock);

    private void ready() {
        when(properties.providerMode()).thenReturn(true);
        when(properties.ready()).thenReturn(true);
    }

    @Test
    void missingCallbackStillQueriesOriginalOrderAndUsesSettlementWithoutCreatingPayment() {
        ready();
        when(mapper.listOrdersDueForQuery(any(), eq(20))).thenReturn(List.of(
                Map.of("merchantOrderId", "VQR-1", "version", 2L)));
        when(mapper.claimOrderQuery(eq("VQR-1"), eq(2L), any())).thenReturn(1);
        var paid = new HdPayGateway.PayOrder("VQR-1", "P-1", 3, new BigDecimal("100"), "BANKQR", "");
        when(gateway.queryPayOrder("VQR-1")).thenReturn(paid);
        scheduler.queryUnsettledOrders();
        verify(settlement).settleOrderQuery("VQR-1", 3L, paid);
        verify(gateway, never()).createPayOrder(any());
        verify(mapper, never()).insertCallbackInbox(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void timeoutRemainsRetryableAndDoesNotBlockLaterOrders() {
        ready();
        when(mapper.listOrdersDueForQuery(any(), eq(20))).thenReturn(List.of(
                Map.of("merchantOrderId", "VQR-1", "version", 2L),
                Map.of("merchantOrderId", "VQR-2", "version", 8L)));
        when(mapper.claimOrderQuery(anyString(), anyLong(), any())).thenReturn(1);
        when(gateway.queryPayOrder("VQR-1")).thenThrow(new HdPayGatewayException("timeout", true));
        var paid = new HdPayGateway.PayOrder("VQR-2", "P-2", 3, new BigDecimal("100"), "BANKQR", "");
        when(gateway.queryPayOrder("VQR-2")).thenReturn(paid);
        scheduler.queryUnsettledOrders();
        verify(mapper).finishOrderQueryAttempt("VQR-1", 3L, "HDPAY_ORDER_QUERY_RETRY_REQUIRED");
        verify(settlement).settleOrderQuery("VQR-2", 9L, paid);
        verify(settlement, never()).settleOrderQuery(eq("VQR-1"), anyLong(), any());
    }

    @Test
    void concurrentWorkerLosingClaimCannotQuery() {
        ready();
        when(mapper.listOrdersDueForQuery(any(), eq(20))).thenReturn(List.of(
                Map.of("merchantOrderId", "VQR-1", "version", 2L)));
        scheduler.queryUnsettledOrders();
        verifyNoInteractions(gateway, settlement);
    }

    @Test
    void disabledOrIncompleteProviderDoesNotScan() {
        scheduler.queryUnsettledOrders();
        when(properties.providerMode()).thenReturn(true);
        scheduler.queryUnsettledOrders();
        verifyNoInteractions(mapper, gateway, settlement);
    }

    @Test
    void failedClaimDoesNotReleaseAnotherWorkersVersion() {
        ready();
        when(mapper.listOrdersDueForQuery(any(), eq(20))).thenReturn(List.of(
                Map.of("merchantOrderId", "VQR-1", "version", 2L)));
        when(mapper.claimOrderQuery(anyString(), anyLong(), any())).thenThrow(new IllegalStateException("db unavailable"));
        scheduler.queryUnsettledOrders();
        verify(mapper, never()).finishOrderQueryAttempt(anyString(), anyLong(), anyString());
        verifyNoInteractions(gateway, settlement);
    }
}
