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
        CopyAudienceTarget audienceTarget,
        String trafficSplit,
        String versionNote,
        long estimatedAudience,
        java.util.Map<String, String> translations,
        /** Real record creation time (ISO-8601, from BaseEntity) or null when the row carries none. */
        String createdAt,
        /** Real record last-update time (ISO-8601) or null; the legacy ts stays exactly as stored. */
        String updatedAt) {
    public CopyVersionRow(
            String copyKey, String version, String status,
            String chain, String ts, String zh,
            String en, String vi, String copyPosition,
            String surface, String audience, CopyAudienceTarget audienceTarget,
            String trafficSplit, String versionNote, long estimatedAudience) {
        this(copyKey, version, status, chain, ts, zh,
                en, vi, copyPosition, surface, audience, audienceTarget,
                trafficSplit, versionNote, estimatedAudience, java.util.Map.of(), null, null);
    }


    public CopyVersionRow(
            String copyKey, String version, String status, String chain, String ts, String zh, String en,
            String vi, String copyPosition, String surface, String audience, CopyAudienceTarget audienceTarget,
            String trafficSplit, String versionNote) {
        this(copyKey, version, status, chain, ts, zh, en, vi, copyPosition, surface, audience, audienceTarget,
                trafficSplit, versionNote, 0L, java.util.Map.of(), null, null);
    }

    public CopyVersionRow(
            String copyKey, String version, String status, String chain, String ts, String zh, String en,
            String vi, String copyPosition, String surface, String audience, String trafficSplit,
            String versionNote) {
        this(copyKey, version, status, chain, ts, zh, en, vi, copyPosition, surface, audience, null,
                trafficSplit, versionNote, 0L, java.util.Map.of(), null, null);
    }
}
