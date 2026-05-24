package ffdd.mission.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.common.outbox.EventConsumerDeliveryService;
import ffdd.common.outbox.EventOutboxMessage;
import ffdd.mission.dto.EarningGeneratedPayload;
import ffdd.mission.dto.MissionConsumeResult;
import ffdd.mission.service.EarningGeneratedMissionService;
import java.math.BigDecimal;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;

class MissionEarningGeneratedRocketListenerTest {
    private final EarningGeneratedMissionService missionService = mock(EarningGeneratedMissionService.class);
    private final EventConsumerDeliveryService deliveryService = mock(EventConsumerDeliveryService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MissionEarningGeneratedRocketListener listener = new MissionEarningGeneratedRocketListener(
            missionService, objectMapper, deliveryService, "127.0.0.1:9876",
            "nexion-earning-generated", "mission-group", 5);

    @Test
    void recordsSuccessfulDeliveryAfterConsumingMission() throws Exception {
        EventOutboxMessage message = earningGeneratedMessage("EVT-5", "EARN-3");
        when(deliveryService.claim(any(EventOutboxMessage.class), eq("mission-group"), eq("nexion-earning-generated"), eq("MSG-5"), eq(0)))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(true, "EVT-5", "PROCESSING", 1));
        when(missionService.consume(any(EarningGeneratedPayload.class)))
                .thenReturn(new MissionConsumeResult(true, 50, "COMPLETED"));

        boolean retry = listener.consumeMessage(rocketMessage("MSG-5", 0, objectMapper.writeValueAsBytes(message)));

        assertThat(retry).isFalse();
        verify(deliveryService).markSuccess("mission-group", "EVT-5", 1);
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
