package ffdd.opsconsole.content.dto;

public record CopyFrameworkUpdateRequest(
        String value,
        String operator,
        String reason,
        String expectedValue) {

    public CopyFrameworkUpdateRequest(String value, String operator, String reason) {
        this(value, operator, reason, "50/50");
    }
}
