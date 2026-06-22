package ffdd.opsconsole.content.domain;

public record NovaStats(
        String todayDelivered,
        String ctr,
        int ctrTarget,
        int onlineChannels,
        int totalChannels,
        String weeklySocial) {
}
