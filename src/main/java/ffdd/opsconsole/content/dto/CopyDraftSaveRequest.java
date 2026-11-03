package ffdd.opsconsole.content.dto;

import ffdd.opsconsole.content.domain.CopyAudienceTarget;

public record CopyDraftSaveRequest(
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
        String reason,
        Long expectedRevision,
        java.util.Map<String, String> translations) {
    public CopyDraftSaveRequest {
        translations = ffdd.opsconsole.content.domain.MessageTranslations.ordered(translations);
    }

    public CopyDraftSaveRequest(
            String version, String surface, String audience,
            CopyAudienceTarget audienceTarget, String trafficSplit, String versionNote,
            String zh, String en, String vi,
            String copyPosition, String operator, String reason,
            Long expectedRevision) {
        this(version, surface, audience, audienceTarget, trafficSplit, versionNote,
                zh, en, vi, copyPosition, operator, reason,
                expectedRevision, null);
    }


    public CopyDraftSaveRequest(
            String version, String surface, String audience, CopyAudienceTarget audienceTarget,
            String trafficSplit, String versionNote, String zh, String en, String vi,
            String copyPosition, String operator, String reason) {
        this(version, surface, audience, audienceTarget, trafficSplit, versionNote, zh, en, vi,
                copyPosition, operator, reason, 1L);
    }

    public CopyDraftSaveRequest(
            String version, String surface, String audience, String trafficSplit, String versionNote,
            String zh, String en, String vi, String copyPosition, String operator, String reason) {
        this(version, surface, audience, null, trafficSplit, versionNote, zh, en, vi,
                copyPosition, operator, reason, 1L);
    }
}
