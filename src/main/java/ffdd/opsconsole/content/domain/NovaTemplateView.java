package ffdd.opsconsole.content.domain;

import ffdd.opsconsole.shared.canonical.RetiredBrandGate;

public record NovaTemplateView(
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
        String status,
        @com.fasterxml.jackson.annotation.JsonIgnore String translationsJson) {
    @org.apache.ibatis.annotations.AutomapConstructor
    public NovaTemplateView {}

    public NovaTemplateView(String channel, String name, String cta, String version, String titleZh,
            String bodyZh, String titleVi, String bodyVi, String titleEn, String bodyEn, String status) {
        this(channel, name, cta, version, titleZh, bodyZh, titleVi, bodyVi, titleEn, bodyEn, status, null);
    }

    @com.fasterxml.jackson.annotation.JsonProperty("translations")
    public java.util.Map<String, NotificationTranslation> translations() {
        return NotificationTranslation.read(translationsJson);
    }

    public boolean carriesRetiredBrand() {
        return RetiredBrandGate.anyCarriesRetiredBrand(
                titleZh, bodyZh, titleVi, bodyVi, titleEn, bodyEn) || translations().values().stream()
                .anyMatch(value -> RetiredBrandGate.anyCarriesRetiredBrand(value.title(), value.body()));
    }
}
