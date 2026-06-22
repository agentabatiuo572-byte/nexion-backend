package ffdd.opsconsole.content.dto;

public record TrustSectionPublishRequest(
        String version,
        String operator,
        String reason) {
}
