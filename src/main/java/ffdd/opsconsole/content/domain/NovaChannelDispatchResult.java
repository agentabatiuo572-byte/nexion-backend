package ffdd.opsconsole.content.domain;

public record NovaChannelDispatchResult(
        boolean dispatched,
        String channel,
        int notificationCount,
        String sourceEventId,
        String reason) {

    public static NovaChannelDispatchResult skipped(String channel, String reason) {
        return new NovaChannelDispatchResult(false, channel, 0, "", reason);
    }
}
