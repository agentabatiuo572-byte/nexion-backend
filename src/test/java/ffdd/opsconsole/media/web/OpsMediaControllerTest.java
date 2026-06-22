package ffdd.opsconsole.media.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.media.application.OpsMediaUploadService;
import ffdd.opsconsole.media.dto.UploadedAsset;
import ffdd.opsconsole.shared.api.ApiResult;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class OpsMediaControllerTest {
    private final OpsMediaUploadService uploadService = mock(OpsMediaUploadService.class);
    private final OpsMediaController controller = new OpsMediaController(uploadService);

    @Test
    void uploadDelegatesToMediaService() {
        MockMultipartFile file = new MockMultipartFile("file", "sku.png", "image/png", new byte[] {1});
        UploadedAsset asset = new UploadedAsset("asset-1", "admin/e/sku.png", "nexion", "image/png", 1L,
                "http://minio.local/sku.png", LocalDateTime.now(), "e", "sku-image");
        when(uploadService.upload(file, "idem-media-1", "E", "sku-image", "SKU", "sku-1", "superadmin")).thenReturn(asset);

        ApiResult<UploadedAsset> result = controller.upload(
                file, "idem-media-1", "E", "sku-image", "SKU", "sku-1", "superadmin");

        assertThat(result.getData()).isSameAs(asset);
        verify(uploadService).upload(file, "idem-media-1", "E", "sku-image", "SKU", "sku-1", "superadmin");
    }

    @Test
    void previewDelegatesToMediaService() {
        UploadedAsset asset = new UploadedAsset("asset-1", "admin/e/sku.png", null, null, null,
                "http://minio.local/sku.png", LocalDateTime.now(), null, null);
        when(uploadService.refreshPreviewUrl("asset-1")).thenReturn(asset);

        ApiResult<UploadedAsset> result = controller.previewUrl("asset-1");

        assertThat(result.getData().previewUrl()).isEqualTo("http://minio.local/sku.png");
        verify(uploadService).refreshPreviewUrl("asset-1");
    }
}
