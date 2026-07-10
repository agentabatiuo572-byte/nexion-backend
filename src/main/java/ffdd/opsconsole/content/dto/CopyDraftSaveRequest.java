package ffdd.opsconsole.content.dto;

public record CopyDraftSaveRequest(
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
