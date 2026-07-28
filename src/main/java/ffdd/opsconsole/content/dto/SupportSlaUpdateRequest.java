package ffdd.opsconsole.content.dto;

public record SupportSlaUpdateRequest(
        Integer firstResponseMins,
        Integer resolutionHours,
        String queue,
        String escalation,
        Long expectedVersion,
        String operator,
        String reason) {
    public SupportSlaUpdateRequest(
            Integer firstResponseMins, Integer resolutionHours, String queue, String escalation,
            String operator, String reason) {
        this(firstResponseMins, resolutionHours, queue, escalation, null, operator, reason);
    }
}
