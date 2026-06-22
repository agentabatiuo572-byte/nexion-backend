package ffdd.opsconsole.content.dto;

public record SessionAdvisorPolicyUpdateRequest(
        String value,
        String operator,
        String reason) {
}
