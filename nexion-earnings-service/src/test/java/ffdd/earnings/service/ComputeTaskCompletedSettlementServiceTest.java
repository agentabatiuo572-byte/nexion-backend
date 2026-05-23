package ffdd.earnings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.common.exception.BizException;
import ffdd.earnings.dto.ComputeTaskCompletedPayload;
import ffdd.earnings.dto.ReceiptSettleRequest;
import java.math.BigDecimal;
import java.util.List;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ComputeTaskCompletedSettlementServiceTest {
    private final EarningsService earningsService = mock(EarningsService.class);
    private final ComputeTaskCompletedSettlementService service =
            new ComputeTaskCompletedSettlementService(earningsService);

    @Test
    void mapsComputeEventToReceiptSettleRequest() {
        LocalDateTime completedAt = LocalDateTime.parse("2026-05-23T12:30:00");
        ComputeTaskCompletedPayload payload = new ComputeTaskCompletedPayload();
        payload.setUserId(10001L);
        payload.setUserDeviceId(7L);
        payload.setReceiptNo("POC-1");
        payload.setRewardUsdt(new BigDecimal("0.018"));
        payload.setRewardNex(new BigDecimal("3.2"));
        payload.setCompletedAt(completedAt);

        ReceiptSettleRequest request = service.toReceiptSettleRequest(payload);

        assertThat(request.getUserId()).isEqualTo(10001L);
        assertThat(request.getUserDeviceId()).isEqualTo(7L);
        assertThat(request.getReceiptNo()).isEqualTo("POC-1");
        assertThat(request.getRewardUsdt()).isEqualByComparingTo("0.018");
        assertThat(request.getRewardNex()).isEqualByComparingTo("3.2");
        assertThat(request.getCompletedAt()).isEqualTo(completedAt);
    }

    @Test
    void rejectsInvalidPayload() {
        ComputeTaskCompletedPayload payload = new ComputeTaskCompletedPayload();
        payload.setUserId(10001L);

        assertThatThrownBy(() -> service.toReceiptSettleRequest(payload))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("receiptNo");
    }

    @Test
    void settlesByReusingReceiptSettlementService() {
        ComputeTaskCompletedPayload payload = new ComputeTaskCompletedPayload();
        payload.setUserId(10001L);
        payload.setUserDeviceId(7L);
        payload.setReceiptNo("POC-1");
        payload.setRewardUsdt(new BigDecimal("0.018"));
        payload.setRewardNex(BigDecimal.ZERO);
        when(earningsService.settleReceipt(any(ReceiptSettleRequest.class)))
                .thenReturn(new ffdd.earnings.dto.ReceiptSettleResponse(List.of(), null));

        service.settle(payload);

        verify(earningsService).settleReceipt(any(ReceiptSettleRequest.class));
    }
}
