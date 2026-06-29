package ffdd.opsconsole.content.facade;

public record NotificationEmergencyDispatchResult(
        String campaignNo,
        String name,
        String tier,
        String audience,
        String status,
        int notificationCount) {
}
