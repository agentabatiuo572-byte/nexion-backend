package ffdd.opsconsole.content.domain;

public record NotificationAudienceEstimateView(
        NotificationAudienceTarget target,
        long estimatedUsers) {
}
