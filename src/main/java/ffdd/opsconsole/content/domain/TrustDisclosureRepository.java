package ffdd.opsconsole.content.domain;

import ffdd.opsconsole.content.dto.DisclosureDraftRequest;
import ffdd.opsconsole.content.dto.DisclosureMatrixRequest;
import ffdd.opsconsole.content.dto.DisclosureJurisdictionCatalogRequest;
import ffdd.opsconsole.content.dto.TrustSectionDraftRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface TrustDisclosureRepository {
    void ensureSeedData(LocalDateTime now);

    List<TrustSectionView> listTrustSections();

    Optional<TrustSectionView> findTrustSection(String sectionKey);

    List<TrustSectionVersionView> listTrustSectionVersions();

    Optional<TrustSectionVersionView> findTrustSectionVersion(String sectionKey, String version);

    TrustSectionVersionView saveTrustSectionDraft(String sectionKey, TrustSectionDraftRequest request, LocalDateTime now);

    void deleteTrustSectionDraft(String sectionKey, String version, LocalDateTime now);

    TrustSectionView publishTrustSectionVersion(String sectionKey, String version, String operator, LocalDateTime now);

    List<TrustSectionFieldView> listSectionFields();

    List<FinancialFieldView> listFinancialFields();

    List<DisclosureJurisdictionView> listJurisdictions();

    List<DisclosureJurisdictionCatalogView> listJurisdictionCatalog();
    default List<DisclosureJurisdictionCatalogView> listActiveJurisdictionCatalog() {
        return listJurisdictionCatalog().stream()
                .filter(row -> "ACTIVE".equalsIgnoreCase(row.status()))
                .toList();
    }

    Optional<DisclosureJurisdictionCatalogView> findJurisdictionCatalog(String jurisdiction);

    boolean jurisdictionCatalogCodeExists(String jurisdiction);

    DisclosureJurisdictionCatalogView createJurisdictionCatalog(
            DisclosureJurisdictionCatalogRequest request, String operator, LocalDateTime now);

    DisclosureJurisdictionCatalogView updateJurisdictionCatalog(
            String jurisdiction, DisclosureJurisdictionCatalogRequest request, String operator, LocalDateTime now);

    DisclosureJurisdictionCatalogView changeJurisdictionCatalogStatus(
            String jurisdiction, String status, long expectedRevision, String operator, LocalDateTime now);

    void deleteJurisdictionCatalog(String jurisdiction, long expectedRevision, String operator, LocalDateTime now);

    Optional<DisclosureJurisdictionView> findJurisdiction(String jurisdiction);

    List<DisclosureChapterView> listChapters(String jurisdiction, String version);

    List<String> listDisclosureVersions();

    List<String> listDisclosureVersionsIncludingDeleted(String jurisdiction);

    List<DisclosureGateActionView> listGateActions();

    Optional<DisclosureDraftView> findLatestDraft();

    Optional<DisclosureDraftView> findDraft(String jurisdiction);

    Optional<DisclosureDraftView> findDisclosureVersion(String jurisdiction, String version);

    List<DisclosureDraftView> listDisclosureVersionItems();

    void updateTrustSection(String sectionKey, String version, String status, String operator, LocalDateTime now);

    DisclosureDraftView saveDisclosureDraft(DisclosureDraftRequest request, String status, String contentHash, LocalDateTime now);

    void deleteDisclosureDraft(String jurisdiction, String version, long expectedRevision,
                               String expectedContentHash, LocalDateTime now);

    void lockDisclosureJurisdiction(String jurisdiction);

    void lockJurisdictionCatalog(String jurisdiction);

    void lockAllJurisdictionCatalogs();

    void publishDisclosure(String jurisdiction, DisclosureDraftRequest request, LocalDateTime now);

    void upsertDisclosureMatrix(DisclosureMatrixRequest request, LocalDateTime now);

    void markDisclosureMatrixUsersStale(String jurisdiction, List<String> countryCodes, String version, LocalDateTime now);

    void archiveDisclosureMatrix(String jurisdiction, String operator, LocalDateTime now);

    void updateGateScope(Set<String> activeKeys, String operator, LocalDateTime now);
}
