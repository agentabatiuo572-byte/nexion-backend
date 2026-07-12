package ffdd.opsconsole.content.domain;

public record NovaSocialDispatchResult(
        boolean dispatched,
        int notificationCount,
        Long eventId,
        String reason) {

    public static NovaSocialDispatchResult skipped(String reason) {
        return new NovaSocialDispatchResult(false, 0, null, reason);
    }
}
