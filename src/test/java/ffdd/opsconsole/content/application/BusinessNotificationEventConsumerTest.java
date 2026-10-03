package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.mapper.BusinessNotificationMapper;
import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class BusinessNotificationEventConsumerTest {
    final EventConsumerDeliveryService delivery = mock(EventConsumerDeliveryService.class);
    final BusinessNotificationMapper mapper = mock(BusinessNotificationMapper.class);
    final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    final BusinessNotificationEventConsumer consumer = new BusinessNotificationEventConsumer(delivery, mapper, new ObjectMapper(), transactions);

    BusinessNotificationEventConsumerTest() {
        when(delivery.claim(any(), anyString(), anyString(), anyString(), eq(0))).thenAnswer(i ->
                new EventConsumerDeliveryService.ConsumerClaim(true, ((EventOutboxMessage)i.getArgument(0)).getEventId(), "PROCESSING", 1));
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(mapper.withdrawalOwner("WD-1")).thenReturn(7L);
        when(mapper.language(7L)).thenReturn("en");
    }

    @Test
    void withdrawalOwnerComesFromStoredOrderAndCopyCoversThreeLanguages() {
        for (String locale : List.of("zh-CN", "en", "vi-VN")) {
            when(mapper.language(7L)).thenReturn(locale);
            consumer.onOutboxMessage(event("withdraw.confirmed", locale));
        }
        var title = ArgumentCaptor.forClass(String.class);
        verify(mapper, times(3)).deliver(anyString(), eq(7L), eq("WITHDRAWAL"), eq("high"), title.capture(),
                anyString(), anyString(), eq("/pages/me/wallet-withdraw-tracking?id=WD-1"));
        assertThat(title.getAllValues()).containsExactly("提现已完成", "Withdrawal completed", "Đã hoàn tất rút tiền");
        verify(transactions, times(3)).commit(any());
    }

    @Test
    void repeatedStateWithNewTransportIdUsesTheSameBusinessIdentity() {
        consumer.onOutboxMessage(event("withdraw.confirmed", "event-1"));
        consumer.onOutboxMessage(event("withdraw.confirmed", "event-2"));
        var identity = ArgumentCaptor.forClass(String.class);
        verify(mapper, times(2)).deliver(identity.capture(), eq(7L), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        assertThat(identity.getAllValues().get(0)).isEqualTo(identity.getAllValues().get(1));
    }

    @Test
    void successfulReceiptSkipsAllFurtherProjectionWork() {
        doReturn(new EventConsumerDeliveryService.ConsumerClaim(false, "done", "SUCCESS", 1))
                .when(delivery).claim(any(), anyString(), anyString(), anyString(), eq(0));
        consumer.onOutboxMessage(event("withdraw.confirmed", "done"));
        verifyNoInteractions(mapper, transactions);
    }

    @Test
    void projectionFailureRollsBackAndLeavesIndependentRetryReceipt() {
        when(mapper.deliver(anyString(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("db unavailable"));
        assertThatThrownBy(() -> consumer.onOutboxMessage(event("withdraw.confirmed", "failed"))).isInstanceOf(IllegalStateException.class);
        verify(transactions).rollback(any());
        verify(transactions, never()).commit(any());
        verify(delivery).markFailure(BusinessNotificationEventConsumer.GROUP, "failed", 0, "BUSINESS_NOTIFICATION_PROJECTION_FAILED");
        verify(delivery, never()).markSuccess(anyString(), anyString(), anyInt());
    }

    @Test
    void missingUntrustedOrConflictingOwnershipNeverDelivers() {
        var conflict = event("withdraw.confirmed", "conflict");
        conflict.setPayload("{\"user_id\":8}");
        consumer.onOutboxMessage(conflict);
        var untrusted = event("withdraw.confirmed", "client");
        untrusted.setServerAuthoritative(false);
        consumer.onOutboxMessage(untrusted);
        when(mapper.withdrawalOwner("WD-1")).thenReturn(null);
        consumer.onOutboxMessage(event("withdraw.confirmed", "missing"));
        verify(mapper, never()).deliver(anyString(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(delivery, times(3)).markSkipped(anyString(), anyString(), eq("AUTHORITATIVE_RECIPIENT_UNAVAILABLE"));
    }

    @Test
    void knownBusinessCategoriesSelectTheirExistingPreferenceKeys() {
        assertThat(NovaBusinessRuntimeService.businessNotificationType("team_event", "commission.paid")).isEqualTo("NOVA_COMMISSION");
        assertThat(NovaBusinessRuntimeService.businessNotificationType("team_event", "referral.bound")).isEqualTo("NOVA_TEAM");
        assertThat(NovaBusinessRuntimeService.businessNotificationType("custom", "staking.claimed")).isEqualTo("NOVA_STAKING");
    }

    static EventOutboxMessage event(String name, String eventId) {
        var message = new EventOutboxMessage();
        message.setEventId(eventId);
        message.setEventType(name);
        message.setAggregateType("WITHDRAWAL");
        message.setAggregateId("WD-1");
        message.setServerAuthoritative(true);
        message.setPayload("{\"user_id\":7,\"reason\":\"INTERNAL_RISK_REASON\"}");
        return message;
    }
}
