package ffdd.opsconsole.risk.facade;

/**
 * Server-only boundary for reporting a rejected client-side state manipulation.
 * Implementations must mark the event as server authoritative; controllers must
 * never accept that trust marker from a request body.
 */
public interface TamperDetectionPublisher {
    default String publish(Long userId, String tamperPath, String attackEffect, String blockedAtEndpoint) {
        return publish(userId, tamperPath, attackEffect, blockedAtEndpoint, null);
    }

    String publish(Long userId, String tamperPath, String attackEffect, String blockedAtEndpoint,
                   ServerFinding serverFinding);

    enum ServerFinding {
        TRIAL_ALREADY_CLAIMED,
        DEVICE_SLOT_CAP_REACHED
    }
}
