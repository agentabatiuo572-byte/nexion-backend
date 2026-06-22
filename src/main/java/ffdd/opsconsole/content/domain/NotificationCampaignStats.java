package ffdd.opsconsole.content.domain;

public record NotificationCampaignStats(
        int monthCampaigns,
        int monthSent,
        int monthScheduled,
        int monthDraft,
        int criticalInflight,
        String avgReadRate,
        String weeklySwipe) {
}
