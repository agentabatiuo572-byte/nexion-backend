package ffdd.opsconsole.content.dto;

public record CopyActionRequest(
        String operator,
        String reason,
        String expectedVersion) {

    public CopyActionRequest(String operator, String reason) {
        this(operator, reason, null);
    }
}
