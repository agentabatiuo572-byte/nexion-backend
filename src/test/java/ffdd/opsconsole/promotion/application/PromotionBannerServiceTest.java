package ffdd.opsconsole.promotion.application;

import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.storage.ObjectStorageService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PromotionBannerServiceTest {
    private static final String KEY = "admin/growth/promotion-banner/20261011/8bbef0bd-8f4e-4c89-a7be-57238a041a36.png";
    private static final String ASSET = encode(KEY);
    private static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/qXcAAAAASUVORK5CYII=");
    private final PromotionMapper db = mock(PromotionMapper.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final PromotionBannerService service = new PromotionBannerService(db, storage);

    @Test void managedImageCanBeSavedAndReadOnlyViaCurrentPublishedVersion() {
        image();
        service.validateAsset(ASSET);
        when(db.one(anyString(), any(Object[].class))).thenReturn(values("status", "SCHEDULED", "contract_json", json(contract())));
        assertArrayEquals(PNG, service.read("PROMO-1", 3).bytes());
        assertEquals("/api/promotions/PROMO-1/banner/3", service.issueUrl("PROMO-1", 3, ASSET));
        assertNull(service.issueUrl("PROMO-1", 3, null));
        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(db).one(sql.capture(), eq("PROMO-1"), eq(3L));
        for (String clause : List.of("v.version=p.active_version", "p.active_version=?", "v.status='PUBLISHED'",
                "p.status IN ('ACTIVE','SCHEDULED')", "v.starts_at<=NOW(6)", "v.ends_at>NOW(6)")) assertTrue(sql.getValue().contains(clause));
    }

    @Test void externalPrivateEvidenceSkuVideoNoncanonicalAndUnknownReferencesAreRejectedBeforeRead() {
        for (String asset : List.of("https://outside.example/image.png", "bad!", ASSET + "=", encode(KEY.replace("promotion-banner", "promotion-evidence")),
                encode(KEY.replace("growth/promotion-banner", "e/sku-image")), encode(KEY.replace(".png", ".mp4")), encode(KEY.replace(".png", ".webp")), encode("private/image.png"))) {
            assertEquals(422, assertThrows(BizException.class, () -> service.validateAsset(asset)).getCode());
        }
        verifyNoInteractions(storage);
        assertEquals(422, assertThrows(BizException.class, () -> service.validateAsset(ASSET)).getCode());
        verify(storage, never()).get(anyString());
    }

    @Test void wrongTypeAndOversizedBytesCannotBeSavedOrServed() {
        when(storage.exists(KEY)).thenReturn(true);
        for (byte[] bytes : List.of(new byte[] {1, 2}, java.util.Arrays.copyOf(PNG, 8), java.util.Arrays.copyOf(PNG, PNG.length - 1), new byte[10 * 1024 * 1024 + 1])) {
            when(storage.get(KEY)).thenAnswer(i -> new ByteArrayInputStream(bytes));
            assertEquals(422, assertThrows(BizException.class, () -> service.validateAsset(ASSET)).getCode());
            when(db.one(anyString(), any(Object[].class))).thenReturn(values("status", "ACTIVE", "contract_json", json(contract())));
            assertEquals(404, assertThrows(BizException.class, () -> service.read("PROMO-1", 3)).getCode());
        }
    }

    @Test void webpReferenceCannotBeProjectedOrServedEvenForAnActivePublishedVersion() {
        var asset = encode(KEY.replace(".png", ".webp"));
        assertNull(service.issueUrl("PROMO-1", 3, asset));
        var contract = contract();
        contract.put("bannerAssetId", asset);
        when(db.one(anyString(), any(Object[].class))).thenReturn(values("status", "ACTIVE", "contract_json", json(contract)));
        assertEquals(404, assertThrows(BizException.class, () -> service.read("PROMO-1", 3)).getCode());
        verifyNoInteractions(storage);
    }

    @ParameterizedTest @ValueSource(strings = {"DRAFT", "PENDING_APPROVAL", "APPROVED", "PAUSED", "ENDED", "ARCHIVED"})
    void stoppedOrUnpublishedActivitiesCannotServeArtwork(String state) {
        when(db.one(anyString(), any(Object[].class))).thenReturn(values("status", state, "contract_json", json(contract())));
        assertEquals(404, assertThrows(BizException.class, () -> service.read("PROMO-1", 3)).getCode());
        verifyNoInteractions(storage);
    }

    @Test void futureExpiredAbsentAndReplacedVersionsDoNotReadStorage() {
        for (boolean future : List.of(true, false)) {
            var contract = contract();
            contract.put(future ? "startsAt" : "endsAt", Instant.now().plusSeconds(future ? 3600 : -1).toString());
            when(db.one(anyString(), any(Object[].class))).thenReturn(values("status", "ACTIVE", "contract_json", json(contract)));
            assertEquals(404, assertThrows(BizException.class, () -> service.read("PROMO-1", 3)).getCode());
        }
        when(db.one(anyString(), any(Object[].class))).thenReturn(null);
        assertEquals(404, assertThrows(BizException.class, () -> service.read("PROMO-1", 2)).getCode());
        verifyNoInteractions(storage);
    }

    @Test void publicProjectionKeepsHistoricalDetailButRemovesStoppedArtwork() {
        var banners = mock(PromotionBannerService.class);
        when(banners.issueUrl("PROMO-1", 3, ASSET)).thenReturn("/api/promotions/PROMO-1/banner/3");
        var publicApi = new PromotionPublicService(db, null, null, banners);
        for (String state : List.of("ACTIVE", "PAUSED", "ENDED", "SCHEDULED")) {
            var c = contract();
            if (state.equals("SCHEDULED")) c.put("startsAt", Instant.now().plusSeconds(3600).toString());
            when(db.requiredRow(anyString(), any(Object[].class))).thenReturn(values("activity_id", "PROMO-1", "active_version", 3, "status", state, "contract_json", json(c)));
            var view = publicApi.get(null, "PROMO-1");
            assertEquals(state, view.get("state"));
            assertEquals(state.equals("ACTIVE") ? "/api/promotions/PROMO-1/banner/3" : null, view.get("bannerImageUrl"));
        }
        verify(banners, times(1)).issueUrl(anyString(), anyLong(), any());
    }

    private void image() {
        when(storage.exists(KEY)).thenReturn(true);
        when(storage.get(KEY)).thenAnswer(i -> new ByteArrayInputStream(PNG));
    }
    private static java.util.Map<String,Object> contract() {
        return values("bannerAssetId", ASSET, "startsAt", Instant.now().minusSeconds(60).toString(), "endsAt", Instant.now().plusSeconds(7200).toString(), "rules", List.of());
    }
    private static String encode(String key) { return Base64.getUrlEncoder().withoutPadding().encodeToString(key.getBytes(StandardCharsets.UTF_8)); }
}
