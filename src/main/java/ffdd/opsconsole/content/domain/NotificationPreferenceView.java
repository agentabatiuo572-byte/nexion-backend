package ffdd.opsconsole.content.domain;

public record NotificationPreferenceView(
        Long userId,
        boolean commission,
        boolean team,
        boolean staking,
        boolean market,
        boolean genesis,
        boolean system,
        EarningPush earningPush) {
    public enum EarningPush { REALTIME, DAILY, HALF_HOURLY, OFF }

    public NotificationPreferenceView(Long userId, boolean commission, boolean team, boolean staking,
            boolean market, boolean genesis, boolean system) {
        this(userId, commission, team, staking, market, genesis, system, EarningPush.DAILY);
    }

    public static NotificationPreferenceView allEnabled() {
        return new NotificationPreferenceView(null, true, true, true, true, true, true, EarningPush.DAILY);
    }

    public static NotificationPreferenceView allEnabled(Long userId) {
        return new NotificationPreferenceView(userId, true, true, true, true, true, true, EarningPush.DAILY);
    }
}
