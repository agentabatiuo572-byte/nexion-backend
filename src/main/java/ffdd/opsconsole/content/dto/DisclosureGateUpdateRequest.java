package ffdd.opsconsole.content.dto;

public record DisclosureGateUpdateRequest(
        String scope,
        String operator,
        String reason) {
}
