package ffdd.opsconsole.content.domain;

public record DisclosureJurisdictionView(
        String code,
        String name,
        String version,
        String status,
        String publishedAt,
        long affected,
        double ackProgress,
        long blocked) {
}
