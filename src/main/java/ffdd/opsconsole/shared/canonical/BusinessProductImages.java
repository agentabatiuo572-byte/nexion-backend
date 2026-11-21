package ffdd.opsconsole.shared.canonical;

import ffdd.opsconsole.device.mapper.AppTradeinMapper;
import ffdd.opsconsole.shared.capacity.ProductSeries;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
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

    public Map<String, String> urls(Collection<String> skuCodes) {
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
        var result = new HashMap<String, String>();
        var signed = new HashMap<String, String>();
        for (String sku : requested) {
            var row = rows.get(sku);
            if (row == null) continue;
            if ((row.assetId() == null || row.assetId().isBlank()) && (row.objectKey() == null || row.objectKey().isBlank())) {
                row = rows.get(ProductSeries.kind(row.seriesCode(), null));
            }
            if (row == null || !ManagedSkuMediaIdentity.isApprovedImageObjectKey(row.objectKey())
                    || !ManagedSkuMediaIdentity.isCanonicalPair(row.assetId(), row.objectKey())) continue;
            var source = row;
            try {
                result.put(sku, signed.computeIfAbsent(source.sku(),
                        key -> images.issueBusinessUrl(key, source.assetId(), source.objectKey())));
            } catch (IllegalStateException ex) {
                log.warn("Product image signing unavailable", ex);
                return result;
            } catch (IllegalArgumentException ex) {
                log.warn("Could not issue product image for SKU {}", source.sku());
            }
        }
        return result;
    }
}
