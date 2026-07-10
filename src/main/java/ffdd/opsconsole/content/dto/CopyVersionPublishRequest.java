package ffdd.opsconsole.content.dto;

public record CopyVersionPublishRequest(
        String version,
        String surface,
        String audience,
        String trafficSplit,
        String versionNote,
        String zh,
        String en,
        String vi,
        String copyPosition,
        String operator,
        String reason) {
}
