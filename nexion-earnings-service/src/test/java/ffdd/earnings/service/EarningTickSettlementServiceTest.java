package ffdd.earnings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.common.exception.BizException;
import ffdd.earnings.domain.EarningTickDevice;
import ffdd.earnings.dto.EarningMilestoneRewardResult;
import ffdd.earnings.dto.EarningTickBatchRequest;
import ffdd.earnings.dto.EarningTickBatchResult;
import ffdd.earnings.dto.EarningTickRequest;
import ffdd.earnings.dto.ReceiptSettleRequest;
import ffdd.earnings.dto.ReceiptSettleResponse;
import ffdd.earnings.mapper.EarningTickDeviceMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EarningTickSettlementServiceTest {
    private final EarningsService earningsService = mock(EarningsService.class);
    private final EarningMilestoneRewardService milestoneRewardService = mock(EarningMilestoneRewardService.class);
    private final EarningTickDeviceMapper tickDeviceMapper = mock(EarningTickDeviceMapper.class);
    private final EarningTickSettlementService service =
            new EarningTickSettlementService(earningsService, milestoneRewardService, tickDeviceMapper, 3600, 100);

    @Test
    void settlesBatchTicksWithDeterministicReceiptAndScansMilestones() {
        when(earningsService.settleReceipt(any(ReceiptSettleRequest.class)))
                .thenReturn(new ReceiptSettleResponse(List.of(), null));
        when(milestoneRewardService.scanAndReward(List.of(10001L)))
                .thenReturn(new EarningMilestoneRewardResult(1, 1, 0, List.of("earn-100"), List.of("EARN-MILESTONE")));

        EarningTickRequest tick = new EarningTickRequest();
        tick.setUserId(10001L);
        tick.setUserDeviceId(7L);
        tick.setTickNo("TICK-202605251000-7");
        tick.setRewardUsdt(new BigDecimal("1.500000"));
        tick.setRewardNex(new BigDecimal("20.000000"));
        tick.setTickAt(LocalDateTime.parse("2026-05-25T10:00:00"));
        EarningTickBatchRequest request = new EarningTickBatchRequest();
        request.setTicks(List.of(tick));

        EarningTickBatchResult result = service.settleBatch(request);

        assertThat(result.getRequested()).isEqualTo(1);
        assertThat(result.getSettled()).isEqualTo(1);
        assertThat(result.getMilestoneRewards()).isEqualTo(1);
        ArgumentCaptor<ReceiptSettleRequest> captor = ArgumentCaptor.forClass(ReceiptSettleRequest.class);
        verify(earningsService).settleReceipt(captor.capture());
        assertThat(captor.getValue().getReceiptNo()).isEqualTo("TICK-202605251000-7");
        assertThat(captor.getValue().getRewardUsdt()).isEqualByComparingTo("1.500000");
        assertThat(captor.getValue().getRewardNex()).isEqualByComparingTo("20.000000");
        verify(milestoneRewardService).scanAndReward(List.of(10001L));
    }

    @Test
    void settlesActiveDeviceTicksUsingConfiguredIntervalShare() {
        EarningTickDevice device = new EarningTickDevice();
        device.setId(7L);
        device.setUserId(10001L);
        device.setDailyUsdt(new BigDecimal("24.000000"));
        device.setDailyNex(new BigDecimal("240.000000"));
        when(tickDeviceMapper.selectTickableDevices(50)).thenReturn(List.of(device));
        when(earningsService.settleReceipt(any(ReceiptSettleRequest.class)))
                .thenReturn(new ReceiptSettleResponse(List.of(), null));
        when(milestoneRewardService.scanAndReward(List.of(10001L))).thenReturn(new EarningMilestoneRewardResult());

        EarningTickBatchResult result = service.settleDeviceTicks(LocalDateTime.parse("2026-05-25T10:15:30"), 50);

        assertThat(result.getRequested()).isEqualTo(1);
        assertThat(result.getSettled()).isEqualTo(1);
        ArgumentCaptor<ReceiptSettleRequest> captor = ArgumentCaptor.forClass(ReceiptSettleRequest.class);
        verify(earningsService).settleReceipt(captor.capture());
        assertThat(captor.getValue().getReceiptNo()).isEqualTo("TICK-20260525100000-7");
        assertThat(captor.getValue().getRewardUsdt()).isEqualByComparingTo("1.000000");
        assertThat(captor.getValue().getRewardNex()).isEqualByComparingTo("10.000000");
        assertThat(captor.getValue().getCompletedAt()).isEqualTo(LocalDateTime.parse("2026-05-25T11:00:00"));
    }

    @Test
    void rejectsOversizedBatchBeforeSettlement() {
        EarningTickRequest tick = new EarningTickRequest();
        tick.setUserId(10001L);
        tick.setRewardUsdt(new BigDecimal("0.010000"));
        EarningTickBatchRequest request = new EarningTickBatchRequest();
        request.setTicks(IntStream.range(0, 501).mapToObj(i -> tick).toList());

        assertThatThrownBy(() -> service.settleBatch(request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("tick batch size");
        verify(earningsService, never()).settleReceipt(any(ReceiptSettleRequest.class));
    }
}
