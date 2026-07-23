package ffdd.opsconsole.content.dto;

public record NotificationCampaignActionRequest(
        String schedule,
        String operator,
        String reason,
        Long expectedRevision) {

    public NotificationCampaignActionRequest(String schedule, String operator, String reason) {
        this(schedule, operator, reason, null);
    }
}
