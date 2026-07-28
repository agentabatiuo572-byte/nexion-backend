package ffdd.opsconsole.content.dto;

public record TrustSectionRollbackRequest(
        String targetVersion,
        String expectedVersion,
        String expectedStatus,
        String operator,
        String reason) {
    public TrustSectionRollbackRequest(
            String targetVersion,
            String operator,
            String reason) {
        this(targetVersion, null, null, operator, reason);
    }
}
