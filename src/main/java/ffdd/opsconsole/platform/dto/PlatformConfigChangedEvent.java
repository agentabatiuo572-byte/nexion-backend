package ffdd.opsconsole.platform.dto;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Public projection invalidation, sent once for a committed configuration write. */
public record PlatformConfigChangedEvent() {
    public static void publish(ApplicationEventPublisher events) {
        if (events == null) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            events.publishEvent(new PlatformConfigChangedEvent());
        } else if (TransactionSynchronizationManager.getSynchronizations().stream().noneMatch(Publication.class::isInstance)) {
            TransactionSynchronizationManager.registerSynchronization(new Publication(events));
        }
    }

    private record Publication(ApplicationEventPublisher events) implements TransactionSynchronization {
        @Override
        public void afterCommit() {
            events.publishEvent(new PlatformConfigChangedEvent());
        }
    }
}
