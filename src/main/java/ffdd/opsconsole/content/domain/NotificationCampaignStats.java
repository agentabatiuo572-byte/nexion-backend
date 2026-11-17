package ffdd.opsconsole.content.domain;

public record NotificationCampaignStats(
        int monthCampaigns,
        int monthSent,
        int monthScheduled,
        int monthDraft,
        int criticalInflight,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String avgReadRate,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String weeklySwipe,
        java.util.Map<String, Object> basis) {
    public NotificationCampaignStats(int monthCampaigns, int monthSent, int monthScheduled, int monthDraft,
            int criticalInflight, String avgReadRate, String weeklySwipe) {
        this(monthCampaigns, monthSent, monthScheduled, monthDraft, criticalInflight,
                avgReadRate, weeklySwipe, java.util.Map.of());
    }
}
