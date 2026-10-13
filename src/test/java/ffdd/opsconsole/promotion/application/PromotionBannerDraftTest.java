package ffdd.opsconsole.promotion.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PromotionBannerDraftTest {
    private final PromotionMapper db = mock(PromotionMapper.class);
    private final PromotionBannerService banners = mock(PromotionBannerService.class);
    private final PromotionAdminService admin = new PromotionAdminService(db,
            new PromotionContractValidator(new ObjectMapper()), null, null, null, null, null, null, null, null, null, null, null, banners);

    @Test void savedBannerBelongsToDraftAndPatchPreservesOrExplicitlyClearsIt() {
        when(db.activity("PROMO-1", true)).thenReturn(values("status", "DRAFT", "draft_version", 2));
        when(db.version("PROMO-1", 2, true)).thenReturn(values("status", "DRAFT", "version", 2, "revision", 1,
                "contract_json", json(values("bannerAssetId", "old", "category", "PROMOTION", "template", "SKU_GIFT"))));
        when(db.write(anyString(), any(Object[].class))).thenReturn(1);
        admin.save("PROMO-1", values("expectedRevision", 1, "reason", "Update promotion artwork", "draft", values("bannerAssetId", "new")));
        var writes = ArgumentCaptor.forClass(Object[].class);
        verify(db).write(startsWith("UPDATE nx_promotion_version SET contract_json"), writes.capture());
        assertEquals("new", parse(writes.getValue()[0]).get("bannerAssetId"));
        verify(banners).validateAsset("new");
        var old = values("bannerAssetId", "old");
        assertEquals("old", PromotionAdminService.mergeDraft(old, values("name", "renamed")).get("bannerAssetId"));
        assertNull(PromotionAdminService.mergeDraft(old, values("bannerAssetId", null)).get("bannerAssetId"));
        assertEquals("old", old.get("bannerAssetId"));
    }

    @Test void invalidBannerCannotCreateOrSaveAnActivity() {
        doThrow(new BizException(422, "PROMOTION_FIELD_INVALID:bannerAssetId")).when(banners).validateAsset("bad");
        assertThrows(BizException.class, () -> admin.create(values("reason", "Create promotion artwork", "draft", values("category", "PROMOTION", "template", "SKU_GIFT", "bannerAssetId", "bad"))));
        when(db.activity("PROMO-1", true)).thenReturn(values("status", "DRAFT", "draft_version", 2));
        when(db.version("PROMO-1", 2, true)).thenReturn(values("status", "DRAFT", "version", 2, "revision", 1, "contract_json", json(values())));
        assertThrows(BizException.class, () -> admin.save("PROMO-1", values("expectedRevision", 1, "reason", "Update promotion artwork", "draft", values("bannerAssetId", "bad"))));
        verify(db, never()).write(anyString(), any(Object[].class));
    }

    @Test void publishRevalidatesBannerBeforePolicyResolutionOrStateWrites() throws Exception {
        var fixture = new ObjectMapper().readTree(java.nio.file.Path.of("src/test/resources/growth-promotions/contracts.json").toFile());
        var contract = parse(fixture.path("templates").get(0).path("value").toString());
        contract.put("bannerAssetId", "deleted");
        var current = values("status", "DRAFT", "draft_version", 2);
        var draft = values("status", "APPROVED", "version", 2, "revision", 3, "contract_json", json(contract));
        when(db.activity("PROMO-1", true)).thenReturn(current);
        when(db.version("PROMO-1", 2, true)).thenReturn(draft);
        doThrow(new BizException(422, "PROMOTION_FIELD_INVALID:bannerAssetId")).when(banners).validateAsset("deleted");
        var failure = assertThrows(BizException.class, () -> admin.versionAction("PROMO-1", "publish",
                values("expectedRevision", 3, "version", 2, "reason", "Publish promotion artwork", "evidenceRefs", List.of("audit-1"))));
        assertTrue(failure.getMessage().contains("bannerAssetId"));
        verify(banners).validateAsset("deleted");
        verify(db, never()).write(anyString(), any(Object[].class));
    }
}
