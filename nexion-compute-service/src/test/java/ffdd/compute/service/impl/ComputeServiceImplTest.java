package ffdd.compute.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.common.api.ApiResult;
import ffdd.common.outbox.EventOutboxService;
import ffdd.compute.client.EarningsClient;
import ffdd.compute.client.dto.EarningsReceiptSettleRequest;
import ffdd.compute.domain.ComputeReceipt;
import ffdd.compute.domain.UserDevice;
import ffdd.compute.dto.ComputeTaskCompletedPayload;
import ffdd.compute.dto.ReceiptCreateRequest;
import ffdd.compute.mapper.ComputeReceiptMapper;
import ffdd.compute.mapper.ComputeTaskMapper;
import ffdd.compute.mapper.UserDeviceMapper;
import ffdd.compute.service.ComputeTaskCompletedEventFactory;
import ffdd.compute.worker.ComputeOutboxRocketPublisher;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ComputeServiceImplTest {
    private final UserDeviceMapper userDeviceMapper = mock(UserDeviceMapper.class);
    private final ComputeTaskMapper taskMapper = mock(ComputeTaskMapper.class);
    private final ComputeReceiptMapper receiptMapper = mock(ComputeReceiptMapper.class);
    private final EarningsClient earningsClient = mock(EarningsClient.class);
    private final EventOutboxService outboxService = mock(EventOutboxService.class);

    @Test
    void createReceiptWritesComputeTaskCompletedOutboxAndKeepsSynchronousSettlement() {
        UserDevice device = new UserDevice();
        device.setId(7L);
        device.setUserId(10001L);
        device.setInstanceNo("UD-ORDER-1");
        device.setIsDeleted(0);
        when(userDeviceMapper.selectById(7L)).thenReturn(device);
        when(earningsClient.settleReceipt(any(EarningsReceiptSettleRequest.class)))
                .thenReturn(ApiResult.ok(Map.of("settled", true)));

        ComputeServiceImpl service = new ComputeServiceImpl(
                userDeviceMapper,
                taskMapper,
                receiptMapper,
                earningsClient,
                outboxService,
                new ComputeTaskCompletedEventFactory(),
                true,
                true);

        ReceiptCreateRequest request = new ReceiptCreateRequest();
        request.setUserDeviceId(7L);
        request.setTaskType("AI_INFERENCE");
        request.setClientName("worker-a");
        request.setRewardUsdt(new BigDecimal("0.018"));
        request.setRewardNex(new BigDecimal("3.2"));

        ComputeReceipt receipt = service.createReceipt(request);

        ArgumentCaptor<ComputeTaskCompletedPayload> payloadCaptor =
                ArgumentCaptor.forClass(ComputeTaskCompletedPayload.class);
        verify(outboxService).publish(
                eq("COMPUTE_RECEIPT"),
                eq(receipt.getReceiptNo()),
                eq(ComputeOutboxRocketPublisher.EVENT_COMPUTE_TASK_COMPLETED),
                payloadCaptor.capture());
        assertThat(payloadCaptor.getValue().getReceiptNo()).isEqualTo(receipt.getReceiptNo());
        assertThat(payloadCaptor.getValue().getRewardUsdt()).isEqualByComparingTo("0.018");
        assertThat(payloadCaptor.getValue().getRewardNex()).isEqualByComparingTo("3.2");
        verify(earningsClient).settleReceipt(any(EarningsReceiptSettleRequest.class));
        assertThat(receipt.getEarningStatus()).isEqualTo("SETTLED");
    }
}
