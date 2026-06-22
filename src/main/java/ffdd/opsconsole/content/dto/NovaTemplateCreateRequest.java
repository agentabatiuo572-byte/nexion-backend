package ffdd.opsconsole.content.dto;

public record NovaTemplateCreateRequest(
        String channel,
        String name,
        String cta,
        String version,
        String operator,
        String reason) {
}
