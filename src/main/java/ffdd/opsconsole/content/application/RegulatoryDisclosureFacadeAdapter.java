package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.DisclosureDraftView;
import ffdd.opsconsole.content.domain.DisclosureJurisdictionCatalogView;
import ffdd.opsconsole.content.domain.DisclosureJurisdictionView;
import ffdd.opsconsole.content.domain.TrustDisclosureRepository;
import ffdd.opsconsole.content.facade.RegulatoryDisclosureFacade;
import ffdd.opsconsole.content.facade.RegulatoryDisclosureSnapshot;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class RegulatoryDisclosureFacadeAdapter implements RegulatoryDisclosureFacade {
    private final TrustDisclosureRepository repository;

    @Override
    public List<RegulatoryDisclosureSnapshot> currentOptions() {
        Map<String, DisclosureJurisdictionCatalogView> activeCatalog = new LinkedHashMap<>();
        repository.listActiveJurisdictionCatalog().forEach(row -> activeCatalog.put(normalize(row.code()), row));
        return repository.listJurisdictions().stream()
                .filter(row -> activeCatalog.containsKey(normalize(row.code())))
                // Catalog ACTIVE means the jurisdiction is enabled. The current
                // disclosure matrix has its own lifecycle and is current only
                // when its immutable version has been published.
                .filter(row -> "published".equalsIgnoreCase(row.status()))
                .map(row -> snapshot(row, activeCatalog.get(normalize(row.code()))))
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    public Optional<RegulatoryDisclosureSnapshot> resolveCurrent(String jurisdictionCode, String disclosureVersion) {
        String code = normalize(jurisdictionCode);
        String version = disclosureVersion == null ? "" : disclosureVersion.trim();
        if (!StringUtils.hasText(code) || !StringUtils.hasText(version)) return Optional.empty();
        return currentOptions().stream()
                .filter(row -> code.equals(normalize(row.jurisdictionCode())))
                .filter(row -> version.equalsIgnoreCase(row.disclosureVersion()))
                .findFirst();
    }

    private Optional<RegulatoryDisclosureSnapshot> snapshot(
            DisclosureJurisdictionView mapping,
            DisclosureJurisdictionCatalogView catalog) {
        DisclosureDraftView disclosure = repository
                .findDisclosureVersion(mapping.code(), mapping.version())
                .orElse(null);
        if (disclosure == null || !List.of("published", "superseded").contains(disclosure.status().toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        return Optional.of(new RegulatoryDisclosureSnapshot(
                normalize(mapping.code()),
                StringUtils.hasText(catalog.name()) ? catalog.name().trim() : mapping.name(),
                mapping.countryCodes() == null ? List.of() : List.copyOf(mapping.countryCodes()),
                mapping.version(),
                disclosure.status(),
                disclosure.contentHash(),
                repository.listChapters(mapping.code(), mapping.version()).size(),
                mapping.publishedAt(),
                mapping.affected(),
                mapping.ackProgress(),
                mapping.blocked()));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
