package ffdd.opsconsole.content.domain;

public record NotificationPreferenceView(
        Long userId,
        boolean commission,
        boolean team,
        boolean staking,
        boolean market,
        boolean genesis,
        boolean system) {
    public static NotificationPreferenceView allEnabled() {
        return new NotificationPreferenceView(null, true, true, true, true, true, true);
    }

    public static NotificationPreferenceView allEnabled(Long userId) {
        return new NotificationPreferenceView(userId, true, true, true, true, true, true);
    }
}
