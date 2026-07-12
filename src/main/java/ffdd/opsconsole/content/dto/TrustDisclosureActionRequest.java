package ffdd.opsconsole.content.dto;

public record TrustDisclosureActionRequest(
        Long expectedRevision,
        String expectedContentHash,
        String operator,
        String reason) {
    public TrustDisclosureActionRequest(String operator, String reason) {
        this(null, null, operator, reason);
    }
}
