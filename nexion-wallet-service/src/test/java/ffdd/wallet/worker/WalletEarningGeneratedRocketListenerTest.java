package ffdd.wallet.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.common.outbox.EventConsumerDeliveryService;
import ffdd.common.outbox.EventOutboxMessage;
import ffdd.wallet.domain.WalletLedger;
import ffdd.wallet.dto.EarningGeneratedPayload;
import ffdd.wallet.service.EarningGeneratedPostingService;
import java.math.BigDecimal;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;

class WalletEarningGeneratedRocketListenerTest {
    private final EarningGeneratedPostingService postingService = mock(EarningGeneratedPostingService.class);
    private final EventConsumerDeliveryService deliveryService = mock(EventConsumerDeliveryService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WalletEarningGeneratedRocketListener listener = new WalletEarningGeneratedRocketListener(
            postingService, objectMapper, deliveryService, "127.0.0.1:9876",
            "nexion-earning-generated", "wallet-group", 5);

    @Test
    void recordsSuccessfulDeliveryAfterPostingLedger() throws Exception {
        EventOutboxMessage message = earningGeneratedMessage("EVT-3", "EARN-1");
        WalletLedger ledger = new WalletLedger();
        ledger.setId(10L);
        when(deliveryService.claim(any(EventOutboxMessage.class), eq("wallet-group"), eq("nexion-earning-generated"), eq("MSG-3"), eq(0)))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(true, "EVT-3", "PROCESSING", 1));
        when(postingService.post(any(EarningGeneratedPayload.class))).thenReturn(ledger);

        boolean retry = listener.consumeMessage(rocketMessage("MSG-3", 0, objectMapper.writeValueAsBytes(message)));

        assertThat(retry).isFalse();
        verify(deliveryService).markSuccess("wallet-group", "EVT-3", 1);
    }

    private EventOutboxMessage earningGeneratedMessage(String eventId, String eventNo) throws Exception {
        EarningGeneratedPayload payload = new EarningGeneratedPayload();
        payload.setEventNo(eventNo);
        payload.setUserId(10001L);
        payload.setAsset("USDT");
        payload.setAmount(new BigDecimal("0.01"));

        EventOutboxMessage message = new EventOutboxMessage();
        message.setEventId(eventId);
        message.setEventType("EarningGenerated");
        message.setAggregateType("EARNING_EVENT");
        message.setAggregateId(eventNo);
        message.setPayload(objectMapper.writeValueAsString(payload));
        return message;
    }

    private MessageExt rocketMessage(String msgId, int reconsumeTimes, byte[] body) {
        MessageExt message = new MessageExt();
        message.setMsgId(msgId);
        message.setReconsumeTimes(reconsumeTimes);
        message.setBody(body);
        return message;
    }
}
