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
        java.util.Map<String, String> translations) {
    public CopyVersionRow(
            String copyKey, String version, String status,
            String chain, String ts, String zh,
            String en, String vi, String copyPosition,
            String surface, String audience, CopyAudienceTarget audienceTarget,
            String trafficSplit, String versionNote, long estimatedAudience) {
        this(copyKey, version, status, chain, ts, zh,
                en, vi, copyPosition, surface, audience, audienceTarget,
                trafficSplit, versionNote, estimatedAudience, java.util.Map.of());
    }


    public CopyVersionRow(
            String copyKey, String version, String status, String chain, String ts, String zh, String en,
            String vi, String copyPosition, String surface, String audience, CopyAudienceTarget audienceTarget,
            String trafficSplit, String versionNote) {
        this(copyKey, version, status, chain, ts, zh, en, vi, copyPosition, surface, audience, audienceTarget,
                trafficSplit, versionNote, 0L);
    }

    public CopyVersionRow(
            String copyKey, String version, String status, String chain, String ts, String zh, String en,
            String vi, String copyPosition, String surface, String audience, String trafficSplit,
            String versionNote) {
        this(copyKey, version, status, chain, ts, zh, en, vi, copyPosition, surface, audience, null,
                trafficSplit, versionNote, 0L);
    }
}
