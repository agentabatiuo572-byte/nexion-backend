package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.mapper.VietQrReceiptEvidenceMapper;
import ffdd.opsconsole.media.dto.UploadedAsset;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.storage.ObjectStorageService;
import ffdd.opsconsole.shared.storage.StoredObject;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class VietQrReceiptEvidenceServiceTest {
    private final VietQrReceiptEvidenceMapper mapper = mock(VietQrReceiptEvidenceMapper.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final VietQrReceiptEvidenceService service = new VietQrReceiptEvidenceService(
            mapper, storage, audit, idempotency,
            Clock.fixed(Instant.parse("2026-08-25T04:00:00Z"), ZoneOffset.UTC));

    @BeforeEach
    void executeIdempotentAction() {
        when(idempotency.execute(anyString(), anyString(), anyString(), eq(UploadedAsset.class), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
    }

    @Test
    void uploadsVerifiedPngAndPersistsOpaqueOneTimeAsset() {
        byte[] png = pngBytes();
        var file = new MockMultipartFile("file", "bank-receipt.png", "image/png", png);
        when(storage.put(anyString(), eq("image/png"), any(InputStream.class), eq((long) png.length)))
                .thenAnswer(invocation -> new StoredObject(
                        "nexion", invocation.getArgument(0), "image/png", png.length));
        when(storage.presignGet(anyString(), any())).thenReturn("http://minio.local/receipt");
        when(mapper.insertAvailableEvidence(
                anyString(), anyString(), eq("VIETQR_RECEIPT"), eq("image/png"),
                eq((long) png.length), anyString(), anyString()))
                .thenReturn(1);

        UploadedAsset result = service.upload(file, "vietqr-proof-upload-1");

        assertThat(result.assetId()).matches("vqr_[0-9a-f]{32}");
        assertThat(result.objectKey())
                .matches("admin/finance/vietqr-receipt/20260825/[0-9a-f-]{36}\\.png");
        assertThat(result.domain()).isEqualTo("finance");
        assertThat(result.usage()).isEqualTo("vietqr-receipt");
        verify(mapper).insertAvailableEvidence(
                eq(result.assetId()), eq(result.objectKey()), eq("VIETQR_RECEIPT"),
                eq("image/png"), eq((long) png.length), anyString(), anyString());
        verify(audit).recordRequired(any());
    }

    @Test
    void rejectsBytesThatOnlyClaimToBePng() {
        var file = new MockMultipartFile(
                "file", "bank-receipt.png", "image/png", "not an image".getBytes());

        assertThatThrownBy(() -> service.upload(file, "vietqr-proof-upload-fake"))
                .isInstanceOf(BizException.class)
                .hasMessage("VIETQR_RECEIPT_IMAGE_SIGNATURE_INVALID");

        verify(storage, never()).put(anyString(), anyString(), any(), anyLong());
        verify(mapper, never()).insertAvailableEvidence(
                anyString(), anyString(), anyString(), anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    void rejectsTruncatedPngThatOnlyHasAValidHeader() {
        byte[] truncated = new byte[] {
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
                0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52
        };
        var file = new MockMultipartFile("file", "bank-receipt.png", "image/png", truncated);

        assertThatThrownBy(() -> service.upload(file, "vietqr-proof-upload-truncated-png"))
                .isInstanceOf(BizException.class)
                .hasMessage("VIETQR_RECEIPT_IMAGE_SIGNATURE_INVALID");

        verify(storage, never()).put(anyString(), anyString(), any(), anyLong());
    }

    @Test
    void rejectsJpegThatOnlyHasStartAndEndMarkers() {
        byte[] forged = new byte[] {
                (byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x00,
                0x00, 0x00, (byte) 0xff, (byte) 0xd9
        };
        var file = new MockMultipartFile("file", "bank-receipt.jpg", "image/jpeg", forged);

        assertThatThrownBy(() -> service.upload(file, "vietqr-proof-upload-forged-jpeg"))
                .isInstanceOf(BizException.class)
                .hasMessage("VIETQR_RECEIPT_IMAGE_SIGNATURE_INVALID");

        verify(storage, never()).put(anyString(), anyString(), any(), anyLong());
    }

    @Test
    void rejectsWebpWhenNoFullDecoderIsAvailableForReceiptEvidence() {
        byte[] forged = new byte[] {
                'R', 'I', 'F', 'F', 0x04, 0x00, 0x00, 0x00,
                'W', 'E', 'B', 'P', 'V', 'P', '8', ' '
        };
        var file = new MockMultipartFile("file", "bank-receipt.webp", "image/webp", forged);

        assertThatThrownBy(() -> service.upload(file, "vietqr-proof-upload-webp"))
                .isInstanceOf(BizException.class)
                .hasMessage("VIETQR_RECEIPT_IMAGE_SIGNATURE_INVALID");

        verify(storage, never()).put(anyString(), anyString(), any(), anyLong());
    }

    @Test
    void rejectsContentTypeOrExtensionThatDisagreesWithMagicBytes() {
        var file = new MockMultipartFile("file", "bank-receipt.jpg", "image/jpeg", pngBytes());

        assertThatThrownBy(() -> service.upload(file, "vietqr-proof-upload-mismatch"))
                .isInstanceOf(BizException.class)
                .hasMessage("VIETQR_RECEIPT_IMAGE_TYPE_MISMATCH");
    }

    @Test
    void claimIsAtomicAndCannotReuseOrForgeAnAssetReference() {
        String assetId = "vqr_123e4567e89b12d3a456426614174000";
        when(mapper.bindAvailableEvidence(
                assetId, "VIETQR_RECEIPT", "VIETQR_RECONCILIATION", "VQR-REC-1", "finance-admin"))
                .thenReturn(1, 0);

        service.claim("media:" + assetId, "VQR-REC-1", "finance-admin");

        assertThatThrownBy(() -> service.claim("media:" + assetId, "VQR-REC-2", "finance-admin"))
                .isInstanceOf(BizException.class)
                .hasMessage("VIETQR_RECEIPT_EVIDENCE_NOT_AVAILABLE");
        assertThatThrownBy(() -> service.claim(
                "media:admin/finance/vietqr-receipt/forged.png", "VQR-REC-3", "finance-admin"))
                .isInstanceOf(BizException.class)
                .hasMessage("VIETQR_RECEIPT_UPLOAD_EVIDENCE_REQUIRED");
    }

    private byte[] pngBytes() {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, 0x00ffffff);
            if (!ImageIO.write(image, "png", output)) {
                throw new AssertionError("PNG writer unavailable");
            }
            return output.toByteArray();
        } catch (IOException ex) {
            throw new AssertionError("Unable to create PNG fixture", ex);
        }
    }
}
