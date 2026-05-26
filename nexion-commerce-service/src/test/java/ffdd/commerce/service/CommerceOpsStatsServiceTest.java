package ffdd.commerce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.commerce.mapper.CommerceOrderMapper;
import ffdd.commerce.mapper.PaymentCallbackEventMapper;
import ffdd.commerce.mapper.PaymentRecordMapper;
import ffdd.commerce.mapper.TradeinApplicationMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CommerceOpsStatsServiceTest {
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-05-26T00:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private final CommerceOrderMapper orderMapper = mock(CommerceOrderMapper.class);
    private final PaymentRecordMapper paymentRecordMapper = mock(PaymentRecordMapper.class);
    private final PaymentCallbackEventMapper callbackEventMapper = mock(PaymentCallbackEventMapper.class);
    private final TradeinApplicationMapper tradeinApplicationMapper = mock(TradeinApplicationMapper.class);
    private final CommerceOpsStatsService service = new CommerceOpsStatsService(
            orderMapper, paymentRecordMapper, callbackEventMapper, tradeinApplicationMapper, CLOCK);

    @Test
    @SuppressWarnings("unchecked")
    void summarizesCommerceKpisWithBoundedWindow() {
        when(orderMapper.selectCount(any()))
                .thenReturn(12L)
                .thenReturn(5L)
                .thenReturn(6L)
                .thenReturn(2L);
        when(paymentRecordMapper.selectCount(any()))
                .thenReturn(14L)
                .thenReturn(7L)
                .thenReturn(3L)
                .thenReturn(2L)
                .thenReturn(1L)
                .thenReturn(4L)
                .thenReturn(2L);
        when(callbackEventMapper.selectCount(any()))
                .thenReturn(10L)
                .thenReturn(1L);
        when(tradeinApplicationMapper.selectCount(any()))
                .thenReturn(3L)
                .thenReturn(2L);

        Map<String, Object> stats = service.summary(120);

        assertThat(stats)
                .containsEntry("service", "nexion-commerce-service")
                .containsEntry("days", 90);
        assertThat((Map<String, Object>) stats.get("orders"))
                .containsEntry("total", 12L)
                .containsEntry("paid", 5L)
                .containsEntry("pendingPayment", 6L)
                .containsEntry("waitingActivation", 2L);
        assertThat((Map<String, Object>) stats.get("payments"))
                .containsEntry("total", 14L)
                .containsEntry("paid", 7L)
                .containsEntry("pending", 3L)
                .containsEntry("failed", 2L)
                .containsEntry("expired", 1L)
                .containsEntry("reconcileDue", 4L)
                .containsEntry("expiredPending", 2L);
        assertThat((Map<String, Object>) stats.get("callbacks"))
                .containsEntry("total", 10L)
                .containsEntry("failed", 1L);
        assertThat((Map<String, Object>) stats.get("tradeins"))
                .containsEntry("total", 3L)
                .containsEntry("submitted", 2L);
    }
}
