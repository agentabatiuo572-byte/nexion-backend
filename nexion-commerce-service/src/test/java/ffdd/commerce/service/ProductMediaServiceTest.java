package ffdd.commerce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.commerce.dto.ProductMediaUploadResponse;
import ffdd.common.exception.BizException;
import ffdd.common.storage.ObjectStorageService;
import ffdd.common.storage.StoredObject;
import java.io.InputStream;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class ProductMediaServiceTest {
    private final ObjectStorageService storageService = mock(ObjectStorageService.class);
    private static final long MAX_UPLOAD_SIZE_BYTES = 50L * 1024 * 1024;
    private final ProductMediaService service = new ProductMediaService(storageService, MAX_UPLOAD_SIZE_BYTES, 900);

    @Test
    void uploadsProductCoverImageToObjectStorage() {
        when(storageService.put(any(), eq("image/png"), any(InputStream.class), eq(11L)))
                .thenAnswer(invocation -> new StoredObject("nexion", invocation.getArgument(0), "image/png", 11L));
        when(storageService.presignGet(any(), any(Duration.class))).thenReturn("http://minio.local/preview");

        ProductMediaUploadResponse response = service.upload("COVER", file("cover.png", "image/png", "image-bytes"));

        assertThat(response.getObjectKey()).startsWith("commerce/products/cover/");
        assertThat(response.getDownloadUrl()).isEqualTo("http://minio.local/preview");
        verify(storageService).put(any(), eq("image/png"), any(InputStream.class), eq(11L));
    }

    @Test
    void rejectsNonImageProductMedia() {
        assertThatThrownBy(() -> service.upload("DETAIL", file("detail.txt", "text/plain", "bad")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Unsupported product media content type");
    }

    @Test
    void uploadsProductDetailVideoToObjectStorage() {
        when(storageService.put(any(), eq("video/mp4"), any(InputStream.class), eq(11L)))
                .thenAnswer(invocation -> new StoredObject("nexion", invocation.getArgument(0), "video/mp4", 11L));
        when(storageService.presignGet(any(), any(Duration.class))).thenReturn("http://minio.local/video-preview");

        ProductMediaUploadResponse response = service.upload("DETAIL", file("demo.mp4", "video/mp4", "video-bytes"));

        assertThat(response.getObjectKey()).startsWith("commerce/products/detail/");
        assertThat(response.getObjectKey()).endsWith(".mp4");
        assertThat(response.getDownloadUrl()).isEqualTo("http://minio.local/video-preview");
        verify(storageService).put(any(), eq("video/mp4"), any(InputStream.class), eq(11L));
    }

    @Test
    void uploadsProductReviewVideoToObjectStorage() {
        when(storageService.put(any(), eq("video/webm"), any(InputStream.class), eq(12L)))
                .thenAnswer(invocation -> new StoredObject("nexion", invocation.getArgument(0), "video/webm", 12L));
        when(storageService.presignGet(any(), any(Duration.class))).thenReturn("http://minio.local/review-video-preview");

        ProductMediaUploadResponse response = service.upload("PRODUCT_REVIEW", file("review.webm", "video/webm", "review-video"));

        assertThat(response.getObjectKey()).startsWith("commerce/products/product_review/");
        assertThat(response.getObjectKey()).endsWith(".webm");
        assertThat(response.getDownloadUrl()).isEqualTo("http://minio.local/review-video-preview");
        verify(storageService).put(any(), eq("video/webm"), any(InputStream.class), eq(12L));
    }

    @Test
    void rejectsMediaLargerThanFiftyMegabytes() {
        ProductMediaService cappedService = new ProductMediaService(storageService, MAX_UPLOAD_SIZE_BYTES, 900);
        byte[] body = new byte[(int) MAX_UPLOAD_SIZE_BYTES + 1];

        assertThatThrownBy(() -> cappedService.upload("DETAIL", new MockMultipartFile("file", "large.mp4", "video/mp4", body)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Product media is too large");
    }

    @Test
    void uploadsGenesisCoverImageToObjectStorage() {
        when(storageService.put(any(), eq("image/webp"), any(InputStream.class), eq(11L)))
                .thenAnswer(invocation -> new StoredObject("nexion", invocation.getArgument(0), "image/webp", 11L));
        when(storageService.presignGet(any(), any(Duration.class))).thenReturn("http://minio.local/genesis-preview");

        ProductMediaUploadResponse response = service.upload("GENESIS_COVER", file("genesis.webp", "image/webp", "image-bytes"));

        assertThat(response.getObjectKey()).startsWith("commerce/genesis/cover/");
        assertThat(response.getDownloadUrl()).isEqualTo("http://minio.local/genesis-preview");
    }

    @Test
    void uploadsGenesisMetadataMediaToObjectStorage() {
        when(storageService.put(any(), eq("video/mp4"), any(InputStream.class), eq(11L)))
                .thenAnswer(invocation -> new StoredObject("nexion", invocation.getArgument(0), "video/mp4", 11L));
        when(storageService.presignGet(any(), any(Duration.class))).thenReturn("http://minio.local/genesis-media-preview");

        ProductMediaUploadResponse response = service.upload("GENESIS_MEDIA", file("teaser.mp4", "video/mp4", "video-bytes"));

        assertThat(response.getObjectKey()).startsWith("commerce/genesis/media/");
        assertThat(response.getObjectKey()).endsWith(".mp4");
        assertThat(response.getDownloadUrl()).isEqualTo("http://minio.local/genesis-media-preview");
    }

    @Test
    void uploadsUserAvatarImageToObjectStorage() {
        when(storageService.put(any(), eq("image/jpeg"), any(InputStream.class), eq(11L)))
                .thenAnswer(invocation -> new StoredObject("nexion", invocation.getArgument(0), "image/jpeg", 11L));
        when(storageService.presignGet(any(), any(Duration.class))).thenReturn("http://minio.local/avatar-preview");

        ProductMediaUploadResponse response = service.upload("USER_AVATAR", file("avatar.jpg", "image/jpeg", "image-bytes"));

        assertThat(response.getObjectKey()).startsWith("auth/users/avatar/");
        assertThat(response.getDownloadUrl()).isEqualTo("http://minio.local/avatar-preview");
    }

    @Test
    void previewsUserAvatarObjectKey() {
        when(storageService.presignGet(eq("auth/users/avatar/avatar.jpg"), any(Duration.class)))
                .thenReturn("http://minio.local/avatar-preview");

        ProductMediaUploadResponse response = service.preview("auth/users/avatar/avatar.jpg");

        assertThat(response.getDownloadUrl()).isEqualTo("http://minio.local/avatar-preview");
    }

    @Test
    void rejectsPreviewOutsideProductMediaPrefix() {
        assertThatThrownBy(() -> service.preview("compliance/kyc/doc.png"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("allowed media object key");
    }

    private MockMultipartFile file(String fileName, String contentType, String body) {
        return new MockMultipartFile("file", fileName, contentType, body.getBytes());
    }
}
