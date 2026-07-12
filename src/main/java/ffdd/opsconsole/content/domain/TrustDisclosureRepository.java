package ffdd.opsconsole.content.domain;

import ffdd.opsconsole.content.dto.DisclosureDraftRequest;
import ffdd.opsconsole.content.dto.DisclosureMatrixRequest;
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

    Optional<DisclosureJurisdictionView> findJurisdiction(String jurisdiction);

    List<DisclosureChapterView> listChapters(String jurisdiction, String version);

    List<String> listDisclosureVersions();

    List<DisclosureGateActionView> listGateActions();

    Optional<DisclosureDraftView> findLatestDraft();

    Optional<DisclosureDraftView> findDraft(String jurisdiction);

    Optional<DisclosureDraftView> findDisclosureVersion(String jurisdiction, String version);

    void updateTrustSection(String sectionKey, String version, String status, String operator, LocalDateTime now);

    void saveDisclosureDraft(DisclosureDraftRequest request, String status, LocalDateTime now);

    void publishDisclosure(String jurisdiction, DisclosureDraftRequest request, LocalDateTime now);

    void upsertDisclosureMatrix(DisclosureMatrixRequest request, LocalDateTime now);

    void archiveDisclosureMatrix(String jurisdiction, String operator, LocalDateTime now);

    void updateGateScope(Set<String> activeKeys, String operator, LocalDateTime now);
}
