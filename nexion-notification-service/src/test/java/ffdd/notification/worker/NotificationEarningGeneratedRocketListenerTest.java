package ffdd.notification.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.common.outbox.EventConsumerDeliveryService;
import ffdd.common.outbox.EventOutboxMessage;
import ffdd.common.rocketmq.RocketMqAclProperties;
import ffdd.notification.domain.Notification;
import ffdd.notification.dto.EarningGeneratedPayload;
import ffdd.notification.service.EarningGeneratedNotificationService;
import java.math.BigDecimal;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;

class NotificationEarningGeneratedRocketListenerTest {
    private final EarningGeneratedNotificationService notificationService = mock(EarningGeneratedNotificationService.class);
    private final EventConsumerDeliveryService deliveryService = mock(EventConsumerDeliveryService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final NotificationEarningGeneratedRocketListener listener = new NotificationEarningGeneratedRocketListener(
            notificationService, objectMapper, deliveryService, "127.0.0.1:9876",
            "nexion-earning-generated", "notification-group", 5, new RocketMqAclProperties());

    @Test
    void recordsSuccessfulDeliveryAfterCreatingNotification() throws Exception {
        EventOutboxMessage message = earningGeneratedMessage("EVT-4", "EARN-2");
        Notification notification = new Notification();
        notification.setId(20L);
        when(deliveryService.claim(any(EventOutboxMessage.class), eq("notification-group"), eq("nexion-earning-generated"), eq("MSG-4"), eq(0)))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(true, "EVT-4", "PROCESSING", 1));
        when(notificationService.create(any(EarningGeneratedPayload.class))).thenReturn(notification);

        boolean retry = listener.consumeMessage(rocketMessage("MSG-4", 0, objectMapper.writeValueAsBytes(message)));

        assertThat(retry).isFalse();
        verify(deliveryService).markSuccess("notification-group", "EVT-4", 1);
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
