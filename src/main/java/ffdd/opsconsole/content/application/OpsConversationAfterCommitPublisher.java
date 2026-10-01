package ffdd.opsconsole.content.application;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Shared REST/socket/bulk invalidation boundary: never advertise an uncommitted message. */
public final class OpsConversationAfterCommitPublisher {
    private OpsConversationAfterCommitPublisher() {}
    public static void publish(ApplicationEventPublisher publisher, ConversationMessageEvent event) {
        if(!TransactionSynchronizationManager.isSynchronizationActive()) {
            publisher.publishEvent(event);return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {publisher.publishEvent(event);}
        });
    }
}
