package ffdd.opsconsole.content.dto;

public record NotificationCampaignActionRequest(
        String schedule,
        String operator,
        String reason) {
}
