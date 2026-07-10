package ffdd.opsconsole.content.domain;

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
        String draftSurface,
        String draftAudience,
        String draftTrafficSplit,
        String draftNote) {
}
