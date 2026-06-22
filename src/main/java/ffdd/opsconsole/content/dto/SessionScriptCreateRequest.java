package ffdd.opsconsole.content.dto;

public record SessionScriptCreateRequest(
        String scriptGroup,
        String text,
        String ctaPath,
        String audience,
        String status,
        String operator,
        String reason) {
}
