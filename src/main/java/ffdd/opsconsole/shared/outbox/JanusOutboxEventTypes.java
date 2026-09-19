package ffdd.opsconsole.shared.outbox;

import java.util.Set;

/**
 * The exact event names the Janus device/strategy producers emit.
 *
 * <p>These facts have no bus consumer: their only delivery path is the outbox
 * table itself, so {@link EventOutboxService#RECORD_ONLY_EVENT_TYPES} retires
 * them. Retirement matches {@code event_type} with {@code IN}, so a producer
 * and the retirement set must agree on the <em>complete</em> name. Producers
 * previously built names by concatenation
 * ({@code "JANUS_STRATEGY_" + action}), which let a bare prefix
 * ({@code "JANUS_STRATEGY_"}) enter the retirement set and silently match
 * nothing. Both sides now share these constants, and
 * {@code OutboxDispatchCoverageTest} fails when a prefix-shaped name is
 * reintroduced.
 */
public final class JanusOutboxEventTypes {
    private JanusOutboxEventTypes() {}

    public static final String DEVICE_COMMAND_ACKED = "JANUS_DEVICE_COMMAND_ACKED";
    public static final String DEVICE_COMMAND_FAILED = "JANUS_DEVICE_COMMAND_FAILED";
    public static final String DEVICE_STATUS_REQUESTED = "JANUS_DEVICE_STATUS_REQUESTED";
    public static final String STRATEGY_PUBLISH = "JANUS_STRATEGY_PUBLISH";
    public static final String STRATEGY_PAUSE = "JANUS_STRATEGY_PAUSE";
    public static final String STRATEGY_ARCHIVE = "JANUS_STRATEGY_ARCHIVE";
    public static final String STRATEGY_COMMAND_PUBLISHED = "JANUS_STRATEGY_COMMAND_PUBLISHED";
    public static final String STRATEGY_DRAFT_DELETED = "JANUS_STRATEGY_DRAFT_DELETED";
    public static final String STRATEGY_ROLLED_BACK = "JANUS_STRATEGY_ROLLED_BACK";

    /** Every name a Janus producer can emit; all of them are record-only. */
    public static final Set<String> ALL = Set.of(
            DEVICE_COMMAND_ACKED,
            DEVICE_COMMAND_FAILED,
            DEVICE_STATUS_REQUESTED,
            STRATEGY_PUBLISH,
            STRATEGY_PAUSE,
            STRATEGY_ARCHIVE,
            STRATEGY_COMMAND_PUBLISHED,
            STRATEGY_DRAFT_DELETED,
            STRATEGY_ROLLED_BACK);
}
