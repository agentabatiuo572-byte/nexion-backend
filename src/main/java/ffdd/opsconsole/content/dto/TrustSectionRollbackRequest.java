package ffdd.opsconsole.content.dto;

public record TrustSectionRollbackRequest(
        String targetVersion,
        String operator,
        String reason) {
}
