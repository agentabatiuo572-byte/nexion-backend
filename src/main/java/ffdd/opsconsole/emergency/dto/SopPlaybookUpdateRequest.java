package ffdd.opsconsole.emergency.dto;

public record SopPlaybookUpdateRequest(
        String summary,
        String name,
        String scene,
        String owner,
        String sla,
        Boolean emergencyTrack,
        String actionSeq,
        String notifyCampaignNo,
        String notifyTemplate,
        String rollback,
        Boolean drillRequired,
        String reason,
        String operator) {
}
