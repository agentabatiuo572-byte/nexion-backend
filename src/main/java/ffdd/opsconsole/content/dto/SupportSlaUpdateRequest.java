package ffdd.opsconsole.content.dto;

public record SupportSlaUpdateRequest(
        Integer firstResponseMins,
        Integer resolutionHours,
        String queue,
        String escalation,
        String operator,
        String reason) {
}
