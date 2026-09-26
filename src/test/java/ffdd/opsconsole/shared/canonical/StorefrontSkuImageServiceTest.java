package ffdd.opsconsole.shared.canonical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.device.mapper.AppTradeinMapper;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.storage.ObjectStorageService;
import ffdd.opsconsole.shared.storage.StorageProperties;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class StorefrontSkuImageServiceTest {
    private static final String OBJECT_KEY =
            "admin/e/sku-image/20260924/d0c0cfb1-9dfe-4d88-b208-d0a3553effdc.png";
    private static final String ASSET_ID = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(OBJECT_KEY.getBytes(StandardCharsets.UTF_8));
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/qXcAAAAASUVORK5CYII=");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-27T00:00:00Z"), ZoneOffset.UTC);

    private final AppTradeinMapper mapper = mock(AppTradeinMapper.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final StorageProperties properties = new StorageProperties();
    private final StorefrontSkuImageService service;

    StorefrontSkuImageServiceTest() {
        properties.setPublicMediaOrigin("https://18.142.169.24");
        properties.setSecretKey("test-only-strong-minio-secret-for-media-links");
        service = new StorefrontSkuImageService(mapper, storage, properties, CLOCK);
    }

    @Test
    void signedPublicImageRouteServesExactImageBytesWithNoInternalAddress() {
        Link link = issue();
        when(mapper.currentStorefrontImage("stellarbox-pro", ASSET_ID, OBJECT_KEY)).thenReturn(1);
        when(storage.get(OBJECT_KEY)).thenAnswer(invocation -> new ByteArrayInputStream(PNG));

        var image = service.read("stellarbox-pro", ASSET_ID, link.expires(), link.signature());
        var response = new StorefrontSkuImageController(service)
                .image("stellarbox-pro", ASSET_ID, link.expires(), link.signature());

        assertThat(link.url()).startsWith("https://18.142.169.24/api/store/media/images/")
                .doesNotContain("127.0.0.1", ":9000", "minio");
        assertThat(image.contentType()).isEqualTo("image/png");
        assertThat(image.bytes()).isEqualTo(PNG);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("image/png");
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getBody()).isEqualTo(PNG);
    }

    @Test
    void tamperedProductAssetExpiryOrSignatureCannotReadStorage() {
        Link link = issue();
        String otherKey = "admin/e/sku-image/20260924/31d2dc24-4688-43e1-9143-a42053f4eeb9.png";
        String otherAsset = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(otherKey.getBytes(StandardCharsets.UTF_8));

        assertUnavailable(() -> service.read("different-sku", ASSET_ID, link.expires(), link.signature()));
        assertUnavailable(() -> service.read("stellarbox-pro", otherAsset, link.expires(), link.signature()));
        assertUnavailable(() -> service.read("stellarbox-pro", ASSET_ID,
                String.valueOf(Long.parseLong(link.expires()) + 1), link.signature()));
        String alteredSignature = (link.signature().startsWith("A") ? "B" : "A")
                + link.signature().substring(1);
        assertUnavailable(() -> service.read("stellarbox-pro", ASSET_ID, link.expires(), alteredSignature));
        verifyNoInteractions(mapper, storage);
    }

    @Test
    void expiredLinkAndReplacedOrDeletedSkuReferenceFailBeforeStorageRead() {
        Link link = issue();
        StorefrontSkuImageService later = new StorefrontSkuImageService(mapper, storage, properties,
                Clock.offset(CLOCK, Duration.ofMinutes(16)));
        assertUnavailable(() -> later.read("stellarbox-pro", ASSET_ID, link.expires(), link.signature()));
        verifyNoInteractions(mapper, storage);

        assertUnavailable(() -> service.read("stellarbox-pro", ASSET_ID, link.expires(), link.signature()));
        verifyNoInteractions(storage);
    }

    @Test
    void rejectsPrivateKeysInsecureOriginsWrongMimeAndOversizeObjects() {
        String privateKey = "admin/finance/receipt/20260924/d0c0cfb1-9dfe-4d88-b208-d0a3553effdc.png";
        String privateAsset = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(privateKey.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> service.issueUrl("stellarbox-pro", privateAsset, privateKey, Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class);
        properties.setPublicMediaOrigin("http://127.0.0.1:9000");
        assertThatThrownBy(() -> service.issueUrl("stellarbox-pro", ASSET_ID, OBJECT_KEY, Duration.ofMinutes(15)))
                .hasMessage("STORE_IMAGE_PUBLIC_ORIGIN_INVALID");
        properties.setPublicMediaOrigin("");
        assertThatThrownBy(() -> service.issueUrl("stellarbox-pro", ASSET_ID, OBJECT_KEY, Duration.ofMinutes(15)))
                .hasMessage("STORE_IMAGE_PUBLIC_ORIGIN_REQUIRED");
        properties.setPublicMediaOrigin("https://18.142.169.24");

        Link link = issue();
        when(mapper.currentStorefrontImage("stellarbox-pro", ASSET_ID, OBJECT_KEY)).thenReturn(1);
        when(storage.get(OBJECT_KEY)).thenReturn(new ByteArrayInputStream("not an image".getBytes(StandardCharsets.UTF_8)))
                .thenReturn(new ByteArrayInputStream(new byte[10 * 1024 * 1024 + 1]));
        assertUnavailable(() -> service.read("stellarbox-pro", ASSET_ID, link.expires(), link.signature()));
        assertUnavailable(() -> service.read("stellarbox-pro", ASSET_ID, link.expires(), link.signature()));
    }

    @Test
    void internalStorageFailureDoesNotExposeItsDetailsToAnonymousImageClients() {
        Link link = issue();
        when(mapper.currentStorefrontImage("stellarbox-pro", ASSET_ID, OBJECT_KEY)).thenReturn(1);
        when(storage.get(OBJECT_KEY)).thenThrow(new BizException(500, "MinIO internal endpoint and credentials failed"));

        assertThatThrownBy(() -> service.read("stellarbox-pro", ASSET_ID, link.expires(), link.signature()))
                .isInstanceOf(BizException.class)
                .hasMessage("STORE_IMAGE_UNAVAILABLE")
                .extracting(ex -> ((BizException) ex).getCode()).isEqualTo(503);
    }

    private Link issue() {
        String url = service.issueUrl("stellarbox-pro", ASSET_ID, OBJECT_KEY, Duration.ofMinutes(15));
        URI uri = URI.create(url);
        String[] query = uri.getRawQuery().split("&");
        return new Link(url, query[0].substring("expires=".length()), query[1].substring("signature=".length()));
    }

    private void assertUnavailable(Runnable read) {
        assertThatThrownBy(read::run).isInstanceOf(BizException.class)
                .hasMessage("STORE_IMAGE_UNAVAILABLE");
    }

    private record Link(String url, String expires, String signature) {}
}
