package ffdd.opsconsole.content.domain;

/**
 * Nova management overview counters.
 *
 * <p>The legacy names {@code todayDelivered}, {@code ctr} and {@code weeklySocial} are kept for existing
 * readers, but they do not mean real deliveries, real clicks or unique sends. The explicit fields below
 * carry the same values with their true meaning: a business-day count of {@code NOVA_SOCIAL} notification
 * records in the delivered-ish statuses, the average stored CTR percentage configured on the channels, and
 * the cumulative dispatch counter total of the social events that were last dispatched within seven
 * days (those events' stored counters, not the number of dispatches that happened in that window).
 */
public record NovaStats(
        String todayDelivered,
        String ctr,
        int ctrTarget,
        int onlineChannels,
        int totalChannels,
        String weeklySocial,
        long socialNotificationRecordsToday,
        String channelStoredCtrAveragePercent,
        long recentSocialEventsCumulativeDispatchCount) {

    /** Existing six-argument callers keep the legacy shape; the explicit fields stay unknown (0 / null). */
    public NovaStats(String todayDelivered, String ctr, int ctrTarget, int onlineChannels,
                     int totalChannels, String weeklySocial) {
        this(todayDelivered, ctr, ctrTarget, onlineChannels, totalChannels, weeklySocial, 0L, null, 0L);
    }
}
