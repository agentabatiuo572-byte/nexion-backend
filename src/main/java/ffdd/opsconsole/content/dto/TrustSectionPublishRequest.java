package ffdd.opsconsole.content.dto;

public record TrustSectionPublishRequest(
        String version,
        Long expectedRevision,
        String expectedVersion,
        String expectedStatus,
        String dataSourceStatement,
        Boolean bilingualConfirmed,
        String operator,
        String reason) {
    public TrustSectionPublishRequest(
            String version,
            Long expectedRevision,
            String dataSourceStatement,
            Boolean bilingualConfirmed,
            String operator,
            String reason) {
        this(version, expectedRevision, null, null, dataSourceStatement, bilingualConfirmed, operator, reason);
    }

    public TrustSectionPublishRequest(
            String version,
            String dataSourceStatement,
            Boolean bilingualConfirmed,
            String operator,
            String reason) {
        this(version, 1L, null, null, dataSourceStatement, bilingualConfirmed, operator, reason);
    }
}
