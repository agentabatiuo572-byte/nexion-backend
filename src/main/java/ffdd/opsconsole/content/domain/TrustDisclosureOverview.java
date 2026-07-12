package ffdd.opsconsole.content.domain;

import java.util.List;
import java.util.Map;

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
        List<String> sources,
        List<DisclosureVersionItem> disclosureVersionItems,
        String nextDisclosureVersion,
        Map<String, String> nextVersionByJurisdiction,
        List<DisclosureJurisdictionView> jurisdictionCatalog) {
}
