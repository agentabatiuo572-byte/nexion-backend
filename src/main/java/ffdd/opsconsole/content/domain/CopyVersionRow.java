package ffdd.opsconsole.content.domain;

public record CopyVersionRow(
        String copyKey,
        String version,
        String status,
        String chain,
        String ts,
        String zh,
        String en,
        String vi,
        String copyPosition,
        String surface,
        String audience,
        String trafficSplit,
        String versionNote) {
}
