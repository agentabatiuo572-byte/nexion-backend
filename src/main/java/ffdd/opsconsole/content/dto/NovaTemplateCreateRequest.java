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
        String reason,
        java.util.Map<String, ffdd.opsconsole.content.domain.NotificationTranslation> translations) {
    public NovaTemplateCreateRequest {
        translations = ffdd.opsconsole.content.domain.MessageTranslations.ordered(translations);
    }

    public NovaTemplateCreateRequest(String channel, String name, String cta, String version,
            String titleZh, String bodyZh, String titleVi, String bodyVi, String titleEn, String bodyEn,
            String operator, String reason) {
        this(channel, name, cta, version, titleZh, bodyZh, titleVi, bodyVi, titleEn, bodyEn, operator, reason, null);
    }
}
