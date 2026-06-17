package ffdd.opsconsole.common.boundary;

import java.time.Instant;

public interface DomainEvent {
    String eventType();

    String aggregateId();

    Instant occurredAt();
}
