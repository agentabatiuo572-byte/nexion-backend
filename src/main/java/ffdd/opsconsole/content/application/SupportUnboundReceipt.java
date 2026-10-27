package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.SupportAgentRepository;
import ffdd.opsconsole.content.mapper.SupportUnboundReceiptMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Tells a customer without a dedicated advisor that the message arrived and an advisor is being arranged,
 * then tries assignment once the message has committed.
 */
@Component
@RequiredArgsConstructor
public class SupportUnboundReceipt {
    private static final Logger LOG = LoggerFactory.getLogger(SupportUnboundReceipt.class);
    private final SupportAgentRepository agents;
    private final SupportUnboundReceiptMapper mapper;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final SupportBindingService bindings;

    @Value("${nexion.support.unbound.receipt-text:已收到您的消息，正在为您安排专属顾问，请稍候。}")
    private String text;

    /** Caller holds the customer lock inside the message transaction. System messages never count as replies. */
    public void sendIfUnbound(Long userId, String conversationNo) {
        if (conversationNo == null || agents.findActiveDedicatedAdvisor(userId).isPresent()) return;
        LocalDateTime now = LocalDateTime.now(clock);
        ConversationMessageEvent event = mapper.insertOnce(conversationNo, text, now) != 1 ? null
                : ConversationMessageEvent.builder()
                        .conversationNo(conversationNo).eventType(ConversationMessageEvent.EventType.MESSAGE)
                        .senderType("SYSTEM").senderName("系统").body(text).ts(now).build();
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            afterCommit(userId, event);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                SupportUnboundReceipt.this.afterCommit(userId, event);
            }
        });
    }

    private void afterCommit(Long userId, ConversationMessageEvent event) {
        if (event != null) events.publishEvent(event);
        try {
            bindings.assignOnInquiry(userId);
        } catch (RuntimeException ex) {
            LOG.warn("Assignment at first inquiry deferred to retry: {}", ex.getClass().getSimpleName());
        }
    }
}
