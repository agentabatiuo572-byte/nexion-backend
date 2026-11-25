package ffdd.opsconsole.shared.canonical;

import ffdd.opsconsole.device.mapper.AppTradeinMapper;
import ffdd.opsconsole.shared.capacity.ProductSeries;
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
        var row = findArtwork(sku);
        if (row != null && needsSeriesFallback(row)) {
            String representative = ProductSeries.kind(row.seriesCode(), null);
            if (representative != null && !representative.equals(sku)) row = findArtwork(representative);
        }
        return url(toArtwork(row));
    }

    private AppTradeinMapper.ProductArtwork findArtwork(String sku) {
        if (sku == null || sku.isBlank()) return null;
        return mapper.businessProductArtwork(List.of(sku)).stream().findFirst().orElse(null);
    }

    public Map<String, Artwork> artwork(Collection<String> skuCodes) {
        var requested = new LinkedHashSet<>(skuCodes);
        requested.removeIf(code -> code == null || code.isBlank());
        if (requested.isEmpty()) return Collections.emptyMap();
        var lookup = new LinkedHashSet<>(requested);
        lookup.addAll(ProductSeries.representativeSkus());
        var rows = new HashMap<String, AppTradeinMapper.ProductArtwork>();
        try {
            for (var row : mapper.businessProductArtwork(lookup)) rows.put(row.sku(), row);
        } catch (DataAccessException ex) {
            log.warn("Product artwork lookup unavailable", ex);
            return Collections.emptyMap();
        }
        var result = new HashMap<String, Artwork>();
        for (String sku : requested) {
            var row = rows.get(sku);
            if (row == null) continue;
            if (needsSeriesFallback(row)) row = rows.get(ProductSeries.kind(row.seriesCode(), null));
            var source = toArtwork(row);
            if (source != null) result.put(sku, source);
        }
        return result;
    }

    private static boolean needsSeriesFallback(AppTradeinMapper.ProductArtwork row) {
        return (row.assetId() == null || row.assetId().isBlank())
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
