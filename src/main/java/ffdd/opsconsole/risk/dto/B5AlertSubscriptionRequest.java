package ffdd.opsconsole.risk.dto;

public record B5AlertSubscriptionRequest(
        Boolean inApp,
        Boolean email,
        Boolean webhook,
        String webhookUrl,
        String operator) {
}
