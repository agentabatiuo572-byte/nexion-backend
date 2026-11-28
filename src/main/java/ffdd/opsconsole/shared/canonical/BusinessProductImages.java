package ffdd.opsconsole.shared.canonical;

import ffdd.opsconsole.device.mapper.AppTradeinMapper;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** Resolves only SKUs selected by a business read, independent of whether they can be purchased. */
@Service
@Slf4j
@RequiredArgsConstructor
public class BusinessProductImages {
    private final AppTradeinMapper mapper;
    private final StorefrontSkuImageService images;
    private final StorefrontSeriesRepresentatives seriesRepresentatives;

    public record Artwork(String sku, String assetId, String objectKey) {}

    public Map<String, String> urls(Collection<String> skuCodes) {
        var signed = new HashMap<Artwork, String>();
        var result = new HashMap<String, String>();
        artwork(skuCodes).forEach((sku, source) -> {
            String url = signed.computeIfAbsent(source, this::url);
            if (url != null) result.put(sku, url);
        });
        return result;
    }

    /** A single-SKU read only depends on that SKU and its own possible series fallback. */
    public String urlForSku(String sku) {
        if (sku == null || sku.isBlank()) return null;
        var row = mapper.businessProductArtwork(List.of(sku)).stream().findFirst().orElse(null);
        return row == null ? null : url(resolveArtwork(List.of(row)).get(row.sku()));
    }

    public Map<String, Artwork> artwork(Collection<String> skuCodes) {
        var requested = new LinkedHashSet<>(skuCodes);
        requested.removeIf(code -> code == null || code.isBlank());
        if (requested.isEmpty()) return Collections.emptyMap();
        try {
            var result = resolveArtwork(mapper.businessProductArtwork(requested));
            result.keySet().retainAll(requested);
            return result;
        } catch (DataAccessException ex) {
            log.warn("Product artwork lookup unavailable", ex);
            return Collections.emptyMap();
        }
    }

    private Map<String, Artwork> resolveArtwork(List<AppTradeinMapper.ProductArtwork> originals) {
        var rows = new HashMap<String, AppTradeinMapper.ProductArtwork>();
        for (var row : originals) rows.put(row.sku(), row);
        addRepresentativeArtwork(rows);
        var result = new HashMap<String, Artwork>();
        rows.forEach((sku, row) -> {
            var source = toArtwork(row);
            if (source != null) result.put(sku, source);
        });
        return result;
    }

    private void addRepresentativeArtwork(Map<String, AppTradeinMapper.ProductArtwork> rows) {
        var missing = rows.values().stream().filter(BusinessProductImages::needsSeriesFallback).toList();
        if (missing.isEmpty()) return;
        try {
            var representatives = seriesRepresentatives.currentProductNos();
            var lookup = missing.stream().map(row -> representatives.get(row.seriesCode()))
                    .filter(sku -> sku != null && !rows.containsKey(sku)).distinct().toList();
            var sources = new HashMap<>(rows);
            if (!lookup.isEmpty()) {
                for (var row : mapper.businessProductArtwork(lookup)) sources.put(row.sku(), row);
            }
            for (var row : missing) {
                var source = sources.get(representatives.get(row.seriesCode()));
                if (source != null && row.seriesCode().equals(source.seriesCode())) rows.put(row.sku(), source);
            }
        } catch (DataAccessException ex) {
            log.warn("Series representative artwork lookup unavailable", ex);
        }
    }

    private static boolean needsSeriesFallback(AppTradeinMapper.ProductArtwork row) {
        return row.seriesCode() != null && !row.seriesCode().isBlank()
                && (row.assetId() == null || row.assetId().isBlank())
                && (row.objectKey() == null || row.objectKey().isBlank());
    }

    private static Artwork toArtwork(AppTradeinMapper.ProductArtwork row) {
        return row != null && ManagedSkuMediaIdentity.isApprovedImageObjectKey(row.objectKey())
                && ManagedSkuMediaIdentity.isCanonicalPair(row.assetId(), row.objectKey())
                ? new Artwork(row.sku(), row.assetId(), row.objectKey()) : null;
    }

    public String url(Artwork artwork) {
        if (artwork == null) return null;
        try {
            return images.issueBusinessUrl(artwork.sku(), artwork.assetId(), artwork.objectKey());
        } catch (IllegalStateException | IllegalArgumentException ex) {
            log.warn("Could not issue product image for SKU {}", artwork.sku(), ex);
            return null;
        }
    }
}
