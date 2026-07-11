package ffdd.opsconsole.content.dto;

public record CopyActionRequest(
        String operator,
        String reason,
        String expectedVersion,
        Long expectedRevision) {

    public CopyActionRequest(String operator, String reason, String expectedVersion) {
        this(operator, reason, expectedVersion, null);
    }

    public CopyActionRequest(String operator, String reason) {
        this(operator, reason, null, null);
    }
}
