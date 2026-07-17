package ffdd.opsconsole.risk.facade;

/**
 * Server-only boundary for reporting a rejected client-side state manipulation.
 * Implementations must mark the event as server authoritative; controllers must
 * never accept that trust marker from a request body.
 */
public interface TamperDetectionPublisher {
    String publish(Long userId, String tamperPath, String attackEffect, String blockedAtEndpoint);
}
