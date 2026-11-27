package ffdd.opsconsole.shared.canonical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.device.mapper.AppTradeinMapper;
import ffdd.opsconsole.device.mapper.AppTradeinMapper.ProductArtwork;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataRetrievalFailureException;

class BusinessProductImagesTest {
    private final AppTradeinMapper mapper = mock(AppTradeinMapper.class);
    private final StorefrontSkuImageService signer = mock(StorefrontSkuImageService.class);
    private final StorefrontSeriesRepresentatives representatives = mock(StorefrontSeriesRepresentatives.class);
    private final BusinessProductImages images = new BusinessProductImages(mapper, signer, representatives);

    @Test
    void ownImageWinsAndMissingVariantsShareOneUrlSignedForTheRepresentative() {
        var original = approved("entry-s1-01");
        var representative = approved("entry-s1-02");
        var variant = new ProductArtwork("entry-s1-03", "S1", null, null);
        var sibling = new ProductArtwork("entry-s1-04", "S1", null, null);
        var invalid = new ProductArtwork("entry-s1-05", "S1", "unapproved", original.objectKey());
        var requested = List.of(original.sku(), variant.sku(), sibling.sku(), invalid.sku());
        when(mapper.businessProductArtwork(new LinkedHashSet<>(requested)))
                .thenReturn(List.of(original, variant, sibling, invalid));
        when(mapper.businessProductArtwork(List.of(variant.sku()))).thenReturn(List.of(variant));
        when(mapper.businessProductArtwork(List.of(representative.sku()))).thenReturn(List.of(representative));
        when(representatives.currentProductNos()).thenReturn(Map.of("S1", representative.sku()));
        when(signer.issueBusinessUrl(original.sku(), original.assetId(), original.objectKey())).thenReturn("own-url");
        when(signer.issueBusinessUrl(representative.sku(), representative.assetId(), representative.objectKey()))
                .thenReturn("representative-url");

        assertThat(images.urls(requested)).isEqualTo(Map.of(original.sku(), "own-url",
                variant.sku(), "representative-url", sibling.sku(), "representative-url"));
        assertThat(images.urlForSku(variant.sku())).isEqualTo("representative-url");
        verify(signer, times(2)).issueBusinessUrl(representative.sku(), representative.assetId(), representative.objectKey());
    }

    @Test
    void absentRepresentativeOrUnapprovedRepresentativeArtworkLeavesTheImageEmpty() {
        var variant = new ProductArtwork("entry-s1-03", "S1", null, null);
        var representative = new ProductArtwork("entry-s1-02", "S1", "asset", "unapproved.png");
        when(mapper.businessProductArtwork(List.of(variant.sku()))).thenReturn(List.of(variant));
        when(mapper.businessProductArtwork(List.of(representative.sku()))).thenReturn(List.of(representative));
        when(representatives.currentProductNos()).thenReturn(Map.of(), Map.of("S1", representative.sku()));

        assertThat(images.urlForSku(variant.sku())).isNull();
        assertThat(images.urlForSku(variant.sku())).isNull();
        verifyNoInteractions(signer);
    }

    @Test
    void unavailableRepresentativeLookupKeepsApprovedOriginalArtwork() {
        var original = approved("entry-s1-01");
        var variant = new ProductArtwork("entry-s1-03", "S1", null, null);
        var requested = List.of(original.sku(), variant.sku());
        when(mapper.businessProductArtwork(new LinkedHashSet<>(requested))).thenReturn(List.of(original, variant));
        when(representatives.currentProductNos()).thenThrow(new DataRetrievalFailureException("catalog unavailable"));
        when(signer.issueBusinessUrl(original.sku(), original.assetId(), original.objectKey())).thenReturn("own-url");

        assertThat(images.urls(requested)).isEqualTo(Map.of(original.sku(), "own-url"));
    }

    private static ProductArtwork approved(String sku) {
        String key = "admin/e/sku-image/20261127/12345678-1234-1234-1234-123456789abc.png";
        String assetId = Base64.getUrlEncoder().withoutPadding().encodeToString(key.getBytes(StandardCharsets.UTF_8));
        return new ProductArtwork(sku, "S1", assetId, key);
    }
}
