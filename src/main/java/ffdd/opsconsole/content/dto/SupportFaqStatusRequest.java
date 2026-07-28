package ffdd.opsconsole.content.dto;

public record SupportFaqStatusRequest(
        String status,
        String expectedStatus,
        Integer expectedVersion,
        String operator,
        String reason) {
    public SupportFaqStatusRequest(String status, String operator, String reason) {
        this(status, null, null, operator, reason);
    }
}
