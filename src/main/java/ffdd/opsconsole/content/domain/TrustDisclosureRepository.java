package ffdd.opsconsole.content.domain;

import ffdd.opsconsole.content.dto.DisclosureDraftRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface TrustDisclosureRepository {
    List<TrustSectionView> listTrustSections();

    Optional<TrustSectionView> findTrustSection(String sectionKey);

    List<TrustSectionFieldView> listSectionFields();

    List<FinancialFieldView> listFinancialFields();

    List<DisclosureJurisdictionView> listJurisdictions();

    Optional<DisclosureJurisdictionView> findJurisdiction(String jurisdiction);

    List<DisclosureChapterView> listChapters(String jurisdiction, String version);

    List<DisclosureGateActionView> listGateActions();

    Optional<DisclosureDraftView> findLatestDraft();

    Optional<DisclosureDraftView> findDraft(String jurisdiction);

    void updateTrustSection(String sectionKey, String version, String status, String operator, LocalDateTime now);

    void saveDisclosureDraft(DisclosureDraftRequest request, String status, LocalDateTime now);

    void publishDisclosure(String jurisdiction, DisclosureDraftRequest request, LocalDateTime now);

    void updateGateScope(Set<String> activeKeys, String operator, LocalDateTime now);
}
