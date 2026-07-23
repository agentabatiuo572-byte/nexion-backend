package ffdd.opsconsole.content.dto;

public record SessionScriptStatusRequest(
        String status,
        String expectedStatus,
        String operator,
        String reason) {
    public SessionScriptStatusRequest(String status, String operator, String reason) {
        this(status, null, operator, reason);
    }
}
