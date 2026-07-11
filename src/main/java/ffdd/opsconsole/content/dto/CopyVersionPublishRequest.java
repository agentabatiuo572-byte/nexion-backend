package ffdd.opsconsole.content.dto;

import ffdd.opsconsole.content.domain.CopyAudienceTarget;

public record CopyVersionPublishRequest(
        String version,
        String surface,
        String audience,
        CopyAudienceTarget audienceTarget,
        String trafficSplit,
        String versionNote,
        String zh,
        String en,
        String vi,
        String copyPosition,
        String operator,
        String reason) {

    public CopyVersionPublishRequest(
            String version, String surface, String audience, String trafficSplit, String versionNote,
            String zh, String en, String vi, String copyPosition, String operator, String reason) {
        this(version, surface, audience, null, trafficSplit, versionNote, zh, en, vi, copyPosition, operator, reason);
    }
}
