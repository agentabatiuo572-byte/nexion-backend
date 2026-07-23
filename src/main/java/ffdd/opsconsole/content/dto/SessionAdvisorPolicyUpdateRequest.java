package ffdd.opsconsole.content.dto;

public record SessionAdvisorPolicyUpdateRequest(
        String value,
        String expectedValue,
        String operator,
        String reason) {
    public SessionAdvisorPolicyUpdateRequest(String value, String operator, String reason) {
        this(value, null, operator, reason);
    }
}
