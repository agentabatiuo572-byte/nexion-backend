package ffdd.opsconsole.content.dto;

public record I18nIntegrityFixRequest(
        String zh,
        String en,
        String operator,
        String reason) {
}
