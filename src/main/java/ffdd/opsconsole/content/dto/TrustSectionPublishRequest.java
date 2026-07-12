package ffdd.opsconsole.content.dto;

public record TrustSectionPublishRequest(
        String version,
        String dataSourceStatement,
        Boolean bilingualConfirmed,
        String operator,
        String reason) {
}
