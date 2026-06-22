package ffdd.opsconsole.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.media.dto.UploadedAsset;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.storage.ObjectStorageService;
import ffdd.opsconsole.shared.storage.StoredObject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

class OpsMediaUploadServiceTest {
    private final ObjectStorageService storageService = mock(ObjectStorageService.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final AdminIdempotencyService idempotencyService = mock(AdminIdempotencyService.class);
    private final OpsMediaUploadService service = service();

    private OpsMediaUploadService service() {
        return new OpsMediaUploadService(
                storageService,
                auditLogService,
                idempotencyService,
                Clock.fixed(Instant.parse("2026-06-17T08:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void uploadsImageToObjectStorageAndReturnsPreviewUrl() {
        executeIdempotentAction();
        MultipartFile file = new MockMultipartFile("file", "sku.png", "image/png", new byte[] {1, 2, 3});
        when(storageService.put(any(), eq("image/png"), any(InputStream.class), eq(3L)))
                .thenReturn(new StoredObject("nexion", "admin/e/sku-image/20260617/asset.png", "image/png", 3));
        when(storageService.presignGet(eq("admin/e/sku-image/20260617/asset.png"), any()))
                .thenReturn("http://minio.local/preview");

        UploadedAsset asset = service.upload(file, "idem-upload-1", "E", "sku-image", "SKU", "sku-1", "superadmin");

        assertThat(asset.bucket()).isEqualTo("nexion");
        assertThat(asset.contentType()).isEqualTo("image/png");
        assertThat(asset.previewUrl()).isEqualTo("http://minio.local/preview");
        assertThat(asset.assetId()).isNotBlank();
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(detailMap(captor.getValue().getDetail()))
                .containsEntry("idempotencyKey", "idem-upload-1");
        verify(idempotencyService).execute(anyString(), eq("idem-upload-1"), anyString(), eq(UploadedAsset.class), any());
    }

    @Test
    void uploadsVideoWhenContentTypeIsInferredFromFilename() {
        executeIdempotentAction();
        MultipartFile file = new MockMultipartFile("file", "launch.MP4", null, new byte[] {1, 2, 3, 4});
        when(storageService.put(any(), eq("video/mp4"), any(InputStream.class), eq(4L)))
                .thenReturn(new StoredObject("nexion", "admin/i/campaign-video/20260617/asset.mp4", "video/mp4", 4));
        when(storageService.presignGet(eq("admin/i/campaign-video/20260617/asset.mp4"), any()))
                .thenReturn("http://minio.local/video-preview");

        UploadedAsset asset = service.upload(
                file,
                "idem-upload-video",
                "I",
                "campaign_video",
                "CAMPAIGN",
                "campaign-1",
                "content-admin");

        assertThat(asset.contentType()).isEqualTo("video/mp4");
        assertThat(asset.objectKey()).endsWith(".mp4");
        assertThat(asset.previewUrl()).isEqualTo("http://minio.local/video-preview");
        assertThat(asset.domain()).isEqualTo("i");
        assertThat(asset.usage()).isEqualTo("campaign-video");
    }

    @Test
    void rejectsUploadWithoutIdempotencyKey() {
        MultipartFile file = new MockMultipartFile("file", "sku.png", "image/png", new byte[] {1});

        assertThatThrownBy(() -> service.upload(file, " ", "A", "notice", null, null, "superadmin"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name())
                .extracting("code")
                .isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
    }

    @Test
    void rejectsUnsupportedMediaType() {
        MultipartFile file = new MockMultipartFile("file", "raw.txt", "text/plain", new byte[] {1});

        assertThatThrownBy(() -> service.upload(file, "idem-upload-2", "A", "notice", null, null, "superadmin"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void rejectsOversizedImageBeforeObjectStorage() {
        MultipartFile file = oversizedFile("hero.png", "image/png", 10L * 1024 * 1024 + 1);

        assertThatThrownBy(() -> service.upload(file, "idem-large-image", "I", "hero", null, null, "content-admin"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("IMAGE_UPLOAD_TOO_LARGE");
        verifyNoInteractions(storageService, auditLogService, idempotencyService);
    }

    @Test
    void rejectsOversizedVideoBeforeObjectStorage() {
        MultipartFile file = oversizedFile("promo.mp4", "video/mp4", 200L * 1024 * 1024 + 1);

        assertThatThrownBy(() -> service.upload(file, "idem-large-video", "I", "promo", null, null, "content-admin"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("VIDEO_UPLOAD_TOO_LARGE");
        verifyNoInteractions(storageService, auditLogService, idempotencyService);
    }

    @Test
    void refreshPreviewUrlDecodesAssetIdAndPresignsStoredObject() {
        String objectKey = "admin/e/sku-image/20260617/asset.png";
        String assetId = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(objectKey.getBytes(StandardCharsets.UTF_8));
        when(storageService.presignGet(eq(objectKey), any())).thenReturn("http://minio.local/fresh-preview");

        UploadedAsset asset = service.refreshPreviewUrl(assetId);

        assertThat(asset.assetId()).isEqualTo(assetId);
        assertThat(asset.objectKey()).isEqualTo(objectKey);
        assertThat(asset.previewUrl()).isEqualTo("http://minio.local/fresh-preview");
        assertThat(asset.expiresAt()).isEqualTo(LocalDateTime.parse("2026-06-17T08:15:00"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> detailMap(Object detail) {
        return (Map<String, Object>) detail;
    }

    @SuppressWarnings("unchecked")
    private void executeIdempotentAction() {
        when(idempotencyService.execute(anyString(), anyString(), anyString(), eq(UploadedAsset.class), any()))
                .thenAnswer(invocation -> ((Supplier<UploadedAsset>) invocation.getArgument(4)).get());
    }

    private MultipartFile oversizedFile(String filename, String contentType, long size) {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn(filename);
        when(file.getContentType()).thenReturn(contentType);
        when(file.getSize()).thenReturn(size);
        return file;
    }
}
