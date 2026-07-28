package ffdd.opsconsole.platform.dto;

public record AdminAccountSecurityBaselineUpdateRequest(
        String value,
        String reason,
        String operator,
        String expectedValue) {
    public AdminAccountSecurityBaselineUpdateRequest(String value, String reason, String operator) {
        this(value, reason, operator, null);
    }
}
