package ffdd.opsconsole.content.dto;

public record NovaTemplateCreateRequest(
        String channel,
        String name,
        String cta,
        String version,
        String titleZh,
        String bodyZh,
        String titleVi,
        String bodyVi,
        String titleEn,
        String bodyEn,
        String operator,
        String reason) {
}
