package ffdd.opsconsole.content.dto;

public record I18nIntegrityFixRequest(
        String messageKey,
        String zh,
        String en,
        String vi,
        String operator,
        String reason) {
}
