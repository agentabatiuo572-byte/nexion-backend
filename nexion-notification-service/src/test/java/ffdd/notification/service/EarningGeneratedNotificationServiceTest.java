package ffdd.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ffdd.notification.domain.Notification;
import ffdd.notification.dto.EarningGeneratedPayload;
import ffdd.notification.mapper.NotificationMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EarningGeneratedNotificationServiceTest {
    private final NotificationMapper notificationMapper = mock(NotificationMapper.class);
    private final NotificationUnreadCounter unreadCounter = mock(NotificationUnreadCounter.class);
    private final EarningGeneratedNotificationService service =
            new EarningGeneratedNotificationService(notificationMapper, unreadCounter);

    @Test
    void createsNotificationForEarningGenerated() {
        EarningGeneratedPayload payload = new EarningGeneratedPayload();
        payload.setEventNo("EARN-POC1-USDT");
        payload.setUserId(10001L);
        payload.setReceiptNo("POC-1");
        payload.setAsset("USDT");
        payload.setAmount(new BigDecimal("0.018"));

        Notification notification = service.create(payload);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper).insert(captor.capture());
        assertThat(notification.getBizNo()).isEqualTo("EarningGenerated:EARN-POC1-USDT");
        assertThat(captor.getValue().getUserId()).isEqualTo(10001L);
        assertThat(captor.getValue().getType()).isEqualTo("EARNING");
        assertThat(captor.getValue().getTitle()).isEqualTo("Earning generated");
        assertThat(captor.getValue().getBody()).contains("0.018", "USDT", "POC-1");
        assertThat(captor.getValue().getReadFlag()).isZero();
        assertThat(captor.getValue().getPushStatus()).isEqualTo("PENDING");
        verify(unreadCounter).increment(10001L);
    }

    @Test
    void returnsExistingNotificationForDuplicateEvent() {
        Notification existing = new Notification();
        existing.setId(9L);
        existing.setBizNo("EarningGenerated:EARN-POC1-USDT");
        when(notificationMapper.selectOne(any())).thenReturn(existing);

        EarningGeneratedPayload payload = new EarningGeneratedPayload();
        payload.setEventNo("EARN-POC1-USDT");
        payload.setUserId(10001L);
        payload.setAsset("USDT");

        Notification notification = service.create(payload);

        assertThat(notification.getId()).isEqualTo(9L);
        verify(notificationMapper, never()).insert(any(Notification.class));
        verifyNoInteractions(unreadCounter);
    }
}
