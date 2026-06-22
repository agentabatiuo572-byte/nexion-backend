package ffdd.opsconsole.content.domain;

import java.util.List;

public record TrustDisclosureOverview(
        TrustDisclosureStats stats,
        List<TrustSectionView> trustSections,
        List<FinancialFieldView> financialFields,
        List<TrustSectionFieldView> sectionFields,
        List<DisclosureJurisdictionView> jurisdictions,
        List<DisclosureChapterView> chapters,
        List<DisclosureGateActionView> gatedActions,
        DisclosureDraftView draft,
        List<String> roleGates,
        List<String> languageScopes,
        String gateScope,
        List<String> sources) {
}
