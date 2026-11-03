package ffdd.opsconsole.content.dto;

public record I18nLocalizedCopyRequest(
        String zh,
        String en,
        String vi,
        String expectedVersion,
        String operator,
        String reason,
        java.util.Map<String, String> translations) {
    public I18nLocalizedCopyRequest {
        translations = ffdd.opsconsole.content.domain.MessageTranslations.ordered(translations);
    }

    public I18nLocalizedCopyRequest(String zh, String en, String vi, String expectedVersion, String operator, String reason) {
        this(zh, en, vi, expectedVersion, operator, reason, null);
    }

    public I18nLocalizedCopyRequest(String zh, String en, String vi, String operator, String reason) {
        this(zh, en, vi, null, operator, reason);
    }
}
