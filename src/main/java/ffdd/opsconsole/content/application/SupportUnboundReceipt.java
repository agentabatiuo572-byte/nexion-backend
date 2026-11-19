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
 * then tries assignment once the message has committed. System messages are not part of the App history (App
 * queries return user and agent messages only); the App shows its own "留言已收到，分配后由专属客服接续" hint,
 * so this row is the console's record of the acknowledgement. Wording follows the App: 专属客服.
 *
 * BUG-519: the acknowledgement text and its SYSTEM sender name follow the customer's effective
 * interface language (InterfaceLanguages codes). English is the fallback for a missing translation or an
 * unknown language, so the legacy single Chinese string can no longer leak Chinese into en/vi threads.
 * The stored conversation message and the live event always carry the same resolved text.
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

    /** Legacy single string: Chinese users only, never the fallback for another language (BUG-519). */
    @Value("${nexion.support.unbound.receipt-text:}")
    private final String legacyText;
    private final org.springframework.core.env.Environment environment;
    private final ffdd.opsconsole.platform.application.PlatformExperienceConfigService experience;

    /** Caller holds the customer lock inside the message transaction. System messages never count as replies. */
    public void sendIfUnbound(Long userId, String conversationNo) {
        if (conversationNo == null || agents.findActiveDedicatedAdvisor(userId).isPresent()) return;
        String language = SupportSystemMessage.effectiveLanguage(mapper.userLanguage(userId),
                experience::interfaceLanguages);
        String text = SupportSystemMessage.unboundReceipt(language, legacyText, environment);
        String senderName = SupportSystemMessage.senderName(language);
        LocalDateTime now = LocalDateTime.now(clock);
        // The persisted row and the live event carry the same resolved text and sender name.
        ConversationMessageEvent event = mapper.insertOnce(conversationNo, senderName, text,
                SupportSystemMessage.RECEIPT_ORIGIN, SupportSystemMessage.RECEIPT_ZH, SupportSystemMessage.RECEIPT_EN,
                SupportSystemMessage.RECEIPT_VI, legacyText, now) != 1 ? null
                : ConversationMessageEvent.builder()
                        .conversationNo(conversationNo).eventType(ConversationMessageEvent.EventType.MESSAGE)
                        .senderType("SYSTEM").senderName(senderName).body(text).ts(now).build();
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
