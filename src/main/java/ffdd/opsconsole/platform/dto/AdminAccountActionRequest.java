package ffdd.opsconsole.platform.dto;

public record AdminAccountActionRequest(
        String reason,
        String operator,
        String expectedVersion) {
    public AdminAccountActionRequest(String reason, String operator) {
        this(reason, operator, null);
    }
}
