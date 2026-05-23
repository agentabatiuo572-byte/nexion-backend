package ffdd.earnings.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ffdd.common.outbox.EventOutboxService;
import ffdd.earnings.client.WalletClient;
import ffdd.earnings.client.dto.WalletPostEarningRequest;
import ffdd.earnings.dto.EarningGeneratedPayload;
import ffdd.earnings.dto.ReceiptSettleRequest;
import ffdd.earnings.dto.ReceiptSettleResponse;
import ffdd.earnings.mapper.EarningEventMapper;
import ffdd.earnings.mapper.EarningSummaryMapper;
import ffdd.earnings.service.EarningGeneratedEventFactory;
import ffdd.earnings.worker.EarningsOutboxRocketPublisher;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EarningsServiceImplTest {
    private final EarningEventMapper eventMapper = mock(EarningEventMapper.class);
    private final EarningSummaryMapper summaryMapper = mock(EarningSummaryMapper.class);
    private final WalletClient walletClient = mock(WalletClient.class);
    private final EventOutboxService outboxService = mock(EventOutboxService.class);

    @Test
    void settleReceiptWritesEarningGeneratedOutboxAndKeepsSynchronousWalletPosting() {
        EarningsServiceImpl service = new EarningsServiceImpl(
                eventMapper,
                summaryMapper,
                walletClient,
                outboxService,
                new EarningGeneratedEventFactory(),
                true,
                true);

        ReceiptSettleRequest request = new ReceiptSettleRequest();
        request.setUserId(10001L);
        request.setUserDeviceId(7L);
        request.setReceiptNo("POC-1");
        request.setRewardUsdt(new BigDecimal("0.018"));
        request.setRewardNex(new BigDecimal("3.2"));
        request.setCompletedAt(LocalDateTime.parse("2026-05-23T12:30:00"));

        ReceiptSettleResponse response = service.settleReceipt(request);

        assertThat(response.getEvents()).hasSize(2);
        ArgumentCaptor<EarningGeneratedPayload> payloadCaptor =
                ArgumentCaptor.forClass(EarningGeneratedPayload.class);
        verify(outboxService).publish(
                eq("EARNING_EVENT"),
                eq("EARN-POC1-USDT"),
                eq(EarningsOutboxRocketPublisher.EVENT_EARNING_GENERATED),
                payloadCaptor.capture());
        verify(outboxService).publish(
                eq("EARNING_EVENT"),
                eq("EARN-POC1-NEX"),
                eq(EarningsOutboxRocketPublisher.EVENT_EARNING_GENERATED),
                any(EarningGeneratedPayload.class));
        assertThat(payloadCaptor.getValue().getEventNo()).isEqualTo("EARN-POC1-USDT");
        assertThat(payloadCaptor.getValue().getAmount()).isEqualByComparingTo("0.018");
        verify(walletClient, times(2)).postEarning(any(WalletPostEarningRequest.class));
    }
}
