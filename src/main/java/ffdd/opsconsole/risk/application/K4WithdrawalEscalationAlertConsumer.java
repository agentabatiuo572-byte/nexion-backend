package ffdd.opsconsole.risk.application;

import ffdd.opsconsole.risk.mapper.K4WithdrawalAlertMapper;
import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Reliably fans one K4 withdrawal escalation to active K4 override operators and super admins. */
@Component
@RequiredArgsConstructor
public class K4WithdrawalEscalationAlertConsumer {
    static final String EVENT_TYPE = "risk.withdraw_escalated";
    static final String CONSUMER_GROUP_PREFIX = "k4-withdrawal-escalation-admin-";
    static final String TOPIC = "spring-local-k4-withdrawal-alert";

    private final EventConsumerDeliveryService deliveryService;
    private final K4WithdrawalAlertMapper alertMapper;

    @EventListener
    @Transactional(rollbackFor = Exception.class)
    public void onOutboxMessage(EventOutboxMessage message) {
        if (message == null || !EVENT_TYPE.equals(message.getEventType())
                || !"WITHDRAWAL".equals(message.getAggregateType())) return;
        List<Long> recipients = alertMapper.activeAlertRecipientIds();
        if (recipients == null || recipients.isEmpty()) {
            throw new IllegalStateException("K4_WITHDRAWAL_ALERT_RECIPIENT_UNAVAILABLE");
        }
        for (Long adminId : recipients) {
            String group = CONSUMER_GROUP_PREFIX + adminId;
            EventConsumerDeliveryService.ConsumerClaim claim = deliveryService.claim(
                    message, group, TOPIC, message.getEventId(), 0);
            if (claim.claimed()) {
                try {
                    alertMapper.insertReceipt(message.getEventId(), adminId,
                            message.getAggregateId(), message.getPayload());
                    if (alertMapper.countReceipt(message.getEventId(), adminId) != 1) {
                        throw new IllegalStateException("K4_WITHDRAWAL_ALERT_RECEIPT_MISSING");
                    }
                    deliveryService.markSuccess(group, claim.eventId(), 1);
                } catch (RuntimeException ex) {
                    deliveryService.markFailure(group, claim.eventId(), 0, ex.getMessage());
                    throw ex;
                }
            } else if (!"SUCCESS".equals(claim.status()) && !"SKIPPED".equals(claim.status())) {
                throw new IllegalStateException("K4_WITHDRAWAL_ALERT_DELIVERY_NOT_COMPLETE:" + claim.status());
            } else if (alertMapper.countReceipt(message.getEventId(), adminId) != 1) {
                throw new IllegalStateException("K4_WITHDRAWAL_ALERT_RECEIPT_MISSING");
            }
        }
    }
}
