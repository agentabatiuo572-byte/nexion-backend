package ffdd.opsconsole.content.dto;

public record I18nVersionActionRequest(
        String expectedVersion,
        String operator,
        String reason) {
}
