package ffdd.opsconsole.content.dto;

public record TrustDisclosureActionRequest(
        Long expectedRevision,
        String expectedContentHash,
        String expectedVersion,
        String expectedStatus,
        String operator,
        String reason) {
    public TrustDisclosureActionRequest(
            Long expectedRevision,
            String expectedContentHash,
            String operator,
            String reason) {
        this(expectedRevision, expectedContentHash, null, null, operator, reason);
    }

    public TrustDisclosureActionRequest(String operator, String reason) {
        this(null, null, null, null, operator, reason);
    }
}
