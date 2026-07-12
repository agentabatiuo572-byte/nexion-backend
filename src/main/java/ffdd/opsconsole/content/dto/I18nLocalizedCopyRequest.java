package ffdd.opsconsole.content.dto;

public record I18nLocalizedCopyRequest(
        String zh,
        String en,
        String vi,
        String expectedVersion,
        String operator,
        String reason) {
    public I18nLocalizedCopyRequest(String zh, String en, String vi, String operator, String reason) {
        this(zh, en, vi, null, operator, reason);
    }
}
