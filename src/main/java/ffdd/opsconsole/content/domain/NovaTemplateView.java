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
        String status) {
    public boolean carriesRetiredBrand() {
        return RetiredBrandGate.anyCarriesRetiredBrand(
                titleZh, bodyZh, titleVi, bodyVi, titleEn, bodyEn);
    }
}
