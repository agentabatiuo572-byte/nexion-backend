package ffdd.opsconsole.content.domain;

import java.util.List;

public record TrustDisclosureOverview(
        TrustDisclosureStats stats,
        List<TrustSectionView> trustSections,
        List<TrustSectionVersionView> trustSectionVersions,
        List<String> pendingTrustSectionKeys,
        List<FinancialFieldView> financialFields,
        List<TrustSectionFieldView> sectionFields,
        List<DisclosureJurisdictionView> jurisdictions,
        List<DisclosureCountryOption> countryOptions,
        List<DisclosureChapterView> chapters,
        List<DisclosureGateActionView> gatedActions,
        DisclosureDraftView draft,
        List<String> roleGates,
        List<String> languageScopes,
        List<String> disclosureVersions,
        String gateScope,
        List<String> sources) {
}
