package ffdd.opsconsole.content.dto;

public record DisclosureGateUpdateRequest(
        String scope,
        String expectedScope,
        String operator,
        String reason) {
    public DisclosureGateUpdateRequest(String scope, String operator, String reason) {
        this(scope, null, operator, reason);
    }
}
