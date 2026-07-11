package ffdd.opsconsole.content.domain;

import java.util.List;

public record CopyContentRow(
        String key,
        String desc,
        String surface,
        String version,
        String status,
        String i18nKey,
        String expId,
        String lastChange,
        String draftVersion,
        String draftZh,
        String draftEn,
        String draftVi,
        String copyPosition,
        String draftCopyPosition,
        String draftSurface,
        String draftAudience,
        CopyAudienceTarget draftAudienceTarget,
        String draftTrafficSplit,
        String draftNote,
        Long revision,
        List<String> usedVersionKeys) {

    public CopyContentRow(
            String key, String desc, String surface, String version, String status, String i18nKey,
            String expId, String lastChange, String draftVersion, String draftZh, String draftEn,
            String draftVi, String copyPosition, String draftCopyPosition, String draftSurface,
            String draftAudience, CopyAudienceTarget draftAudienceTarget, String draftTrafficSplit,
            String draftNote, Long revision) {
        this(key, desc, surface, version, status, i18nKey, expId, lastChange, draftVersion, draftZh,
                draftEn, draftVi, copyPosition, draftCopyPosition, draftSurface, draftAudience,
                draftAudienceTarget, draftTrafficSplit, draftNote, revision, List.of());
    }

    public CopyContentRow(
            String key, String desc, String surface, String version, String status, String i18nKey,
            String expId, String lastChange, String draftVersion, String draftZh, String draftEn,
            String draftVi, String copyPosition, String draftSurface, String draftAudience,
            String draftTrafficSplit, String draftNote) {
        this(key, desc, surface, version, status, i18nKey, expId, lastChange, draftVersion, draftZh,
                draftEn, draftVi, copyPosition, null, draftSurface, draftAudience, null, draftTrafficSplit,
                draftNote, null, List.of());
    }
}
