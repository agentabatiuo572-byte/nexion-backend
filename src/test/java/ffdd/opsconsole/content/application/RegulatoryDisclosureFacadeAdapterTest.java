package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.domain.DisclosureChapterView;
import ffdd.opsconsole.content.domain.DisclosureDraftView;
import ffdd.opsconsole.content.domain.DisclosureJurisdictionCatalogView;
import ffdd.opsconsole.content.domain.DisclosureJurisdictionView;
import ffdd.opsconsole.content.domain.TrustDisclosureRepository;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RegulatoryDisclosureFacadeAdapterTest {
    private final TrustDisclosureRepository repository = mock(TrustDisclosureRepository.class);
    private final RegulatoryDisclosureFacadeAdapter adapter = new RegulatoryDisclosureFacadeAdapter(repository);

    @Test
    void activeCatalogWithPublishedCurrentMatrixExposesItsSevenChapterSnapshot() {
        when(repository.listActiveJurisdictionCatalog()).thenReturn(List.of(
                new DisclosureJurisdictionCatalogView("SBV", "越南国家银行", "ACTIVE", 1L, 1L, true, "admin", "2026-07-23")));
        when(repository.listJurisdictions()).thenReturn(List.of(
                new DisclosureJurisdictionView(
                        "SBV", "越南国家银行", List.of("VN"), "v1", "published",
                        "2026-07-23", 12L, 75D, 1L)));
        DisclosureDraftView publishedDraft = mockDraft("published");
        when(repository.findDisclosureVersion("SBV", "v1")).thenReturn(Optional.of(publishedDraft));
        when(repository.listChapters("SBV", "v1"))
                .thenReturn(Collections.nCopies(7, mock(DisclosureChapterView.class)));

        var options = adapter.currentOptions();

        assertThat(options).singleElement().satisfies(option -> assertThat(option)
                .extracting(
                        value -> value.jurisdictionCode(),
                        value -> value.disclosureVersion(),
                        value -> value.chapterCount(),
                        value -> value.contentHash())
                .containsExactly("SBV", "v1", 7, "hash-v1"));
    }

    @Test
    void draftMatrixIsNotExposedEvenWhenItsCatalogIsActive() {
        when(repository.listActiveJurisdictionCatalog()).thenReturn(List.of(
                new DisclosureJurisdictionCatalogView("SBV", "越南国家银行", "ACTIVE", 1L, 1L, true, "admin", "2026-07-23")));
        when(repository.listJurisdictions()).thenReturn(List.of(
                new DisclosureJurisdictionView(
                        "SBV", "越南国家银行", List.of("VN"), "v2", "draft",
                        "", 0L, 0D, 0L)));

        assertThat(adapter.currentOptions()).isEmpty();
    }

    private DisclosureDraftView mockDraft(String status) {
        DisclosureDraftView draft = mock(DisclosureDraftView.class);
        when(draft.status()).thenReturn(status);
        when(draft.contentHash()).thenReturn("hash-v1");
        return draft;
    }
}
