package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.finance.mapper.VietQrReceiptEvidenceMapper;
import ffdd.opsconsole.media.dto.UploadedAsset;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import ffdd.opsconsole.shared.storage.ObjectStorageService;
import ffdd.opsconsole.shared.storage.StoredObject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class VietQrReceiptEvidenceService {
    private static final String PURPOSE = "VIETQR_RECEIPT";
    private static final String IDEMPOTENCY_SCOPE = "D1_VIETQR_RECEIPT_EVIDENCE_UPLOAD";
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final long MAX_IMAGE_PIXELS = 16_000_000L;
    private static final Duration PREVIEW_EXPIRY = Duration.ofMinutes(15);

    private final VietQrReceiptEvidenceMapper mapper;
    private final ObjectStorageService storage;
    private final AuditLogService audit;
    private final AdminIdempotencyService idempotency;
    private final Clock clock;

    public UploadedAsset upload(MultipartFile file, String idempotencyKey) {
        String key = requireIdempotencyKey(idempotencyKey);
        if (file == null || file.isEmpty()) {
            validation("UPLOAD_FILE_REQUIRED");
        }
        if (file.getSize() <= 0 || file.getSize() > MAX_BYTES) {
            validation("VIETQR_RECEIPT_FILE_TOO_LARGE");
        }
        byte[] bytes = bytes(file);
        DetectedImage detected = detectImage(bytes);
        validateDeclaredTypeAndFilename(file, detected);
        String actor = actor();
        String requestHash = sha256(bytes, file.getOriginalFilename(), detected.contentType());
        return idempotency.execute(
                IDEMPOTENCY_SCOPE,
                key,
                requestHash,
                UploadedAsset.class,
                () -> uploadNew(bytes, detected, actor, key));
    }

    @Transactional
    public void claim(String evidenceRef, String reconciliationNo, String boundBy) {
        String assetId = assetId(evidenceRef);
        if (!StringUtils.hasText(reconciliationNo)) {
            validation("VIETQR_RECONCILIATION_NUMBER_REQUIRED");
        }
        String actor = StringUtils.hasText(boundBy) ? boundBy.trim() : actor();
        if (mapper.bindAvailableEvidence(
                assetId, PURPOSE, "VIETQR_RECONCILIATION", reconciliationNo.trim(), actor) != 1) {
            throw new BizException(409, "VIETQR_RECEIPT_EVIDENCE_NOT_AVAILABLE");
        }
    }

    public void validateReferenceSyntax(String evidenceRef) {
        assetId(evidenceRef);
    }

    private UploadedAsset uploadNew(
            byte[] bytes, DetectedImage detected, String actor, String idempotencyKey) {
        LocalDateTime now = LocalDateTime.now(clock);
        String assetId = "vqr_" + UUID.randomUUID().toString().replace("-", "");
        String objectKey = "admin/finance/vietqr-receipt/%04d%02d%02d/%s.%s".formatted(
                now.getYear(), now.getMonthValue(), now.getDayOfMonth(),
                UUID.randomUUID(), detected.extension());
        StoredObject stored = storage.put(
                objectKey, detected.contentType(), new ByteArrayInputStream(bytes), bytes.length);
        try {
            String digest = sha256(bytes);
            if (mapper.insertAvailableEvidence(
                    assetId, stored.getObjectKey(), PURPOSE, detected.contentType(),
                    bytes.length, digest, actor) != 1) {
                throw new BizException(409, "VIETQR_RECEIPT_EVIDENCE_PERSIST_FAILED");
            }
            audit.recordRequired(AuditLogWriteRequest.builder()
                    .action("VIETQR_RECEIPT_EVIDENCE_UPLOADED")
                    .resourceType("VIETQR_RECEIPT_EVIDENCE")
                    .resourceId(assetId)
                    .actorType("ADMIN")
                    .actorUsername(actor)
                    .result("SUCCESS")
                    .riskLevel("HIGH")
                    .detail(Map.of(
                            "assetId", assetId,
                            "objectKey", stored.getObjectKey(),
                            "contentType", detected.contentType(),
                            "sizeBytes", bytes.length,
                            "contentSha256", digest,
                            "purpose", PURPOSE,
                            "idempotencyKey", idempotencyKey))
                    .build());
            String previewUrl = storage.presignGet(stored.getObjectKey(), PREVIEW_EXPIRY);
            return new UploadedAsset(
                    assetId, stored.getObjectKey(), stored.getBucket(), detected.contentType(),
                    (long) bytes.length, previewUrl, now.plus(PREVIEW_EXPIRY),
                    "finance", "vietqr-receipt");
        } catch (RuntimeException ex) {
            storage.removeQuietly(stored.getObjectKey());
            throw ex;
        }
    }

    private DetectedImage detectImage(byte[] bytes) {
        if (bytes.length >= 8
                && unsigned(bytes[0]) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4e
                && bytes[3] == 0x47 && bytes[4] == 0x0d && bytes[5] == 0x0a
                && bytes[6] == 0x1a && bytes[7] == 0x0a) {
            if (hasCompletePngStructure(bytes) && decodesAs(bytes, "PNG")) {
                return new DetectedImage("image/png", "png");
            }
            validation("VIETQR_RECEIPT_IMAGE_SIGNATURE_INVALID");
        }
        if (bytes.length >= 4
                && unsigned(bytes[0]) == 0xff && unsigned(bytes[1]) == 0xd8
                && unsigned(bytes[2]) == 0xff
                && unsigned(bytes[bytes.length - 2]) == 0xff
                && unsigned(bytes[bytes.length - 1]) == 0xd9) {
            if (decodesAs(bytes, "JPEG")) {
                return new DetectedImage("image/jpeg", "jpg");
            }
            validation("VIETQR_RECEIPT_IMAGE_SIGNATURE_INVALID");
        }
        validation("VIETQR_RECEIPT_IMAGE_SIGNATURE_INVALID");
        throw new IllegalStateException("unreachable");
    }

    private boolean hasCompletePngStructure(byte[] bytes) {
        int offset = 8;
        boolean seenHeader = false;
        boolean seenImageData = false;
        while (offset <= bytes.length - 12) {
            long chunkLength = readUnsignedInt(bytes, offset);
            if (chunkLength > Integer.MAX_VALUE
                    || chunkLength > bytes.length - (long) offset - 12L) {
                return false;
            }
            int dataLength = (int) chunkLength;
            int typeOffset = offset + 4;
            String type = new String(bytes, typeOffset, 4, StandardCharsets.US_ASCII);
            int crcOffset = typeOffset + 4 + dataLength;
            CRC32 crc = new CRC32();
            crc.update(bytes, typeOffset, 4 + dataLength);
            if (crc.getValue() != readUnsignedInt(bytes, crcOffset)) {
                return false;
            }
            if (!seenHeader) {
                if (!"IHDR".equals(type) || dataLength != 13) {
                    return false;
                }
                seenHeader = true;
            } else if ("IHDR".equals(type)) {
                return false;
            }
            if ("IDAT".equals(type)) {
                seenImageData = true;
            }
            offset = crcOffset + 4;
            if ("IEND".equals(type)) {
                return dataLength == 0 && seenHeader && seenImageData && offset == bytes.length;
            }
        }
        return false;
    }

    private boolean decodesAs(byte[] bytes, String expectedFormat) {
        ImageReader reader = null;
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                return false;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return false;
            }
            reader = readers.next();
            reader.setInput(input, true, true);
            if (!expectedFormat.equalsIgnoreCase(reader.getFormatName())) {
                return false;
            }
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            if (width <= 0 || height <= 0 || (long) width * height > MAX_IMAGE_PIXELS) {
                return false;
            }
            return reader.read(0) != null;
        } catch (IOException | RuntimeException ex) {
            return false;
        } finally {
            if (reader != null) {
                reader.dispose();
            }
        }
    }

    private long readUnsignedInt(byte[] bytes, int offset) {
        return ((long) unsigned(bytes[offset]) << 24)
                | ((long) unsigned(bytes[offset + 1]) << 16)
                | ((long) unsigned(bytes[offset + 2]) << 8)
                | unsigned(bytes[offset + 3]);
    }

    private void validateDeclaredTypeAndFilename(MultipartFile file, DetectedImage detected) {
        String declared = StringUtils.hasText(file.getContentType())
                ? file.getContentType().trim().toLowerCase(Locale.ROOT)
                : "";
        String filename = StringUtils.hasText(file.getOriginalFilename())
                ? file.getOriginalFilename().trim().toLowerCase(Locale.ROOT)
                : "";
        String extensionType = filename.endsWith(".png") ? "image/png"
                : filename.endsWith(".jpg") || filename.endsWith(".jpeg") ? "image/jpeg"
                : "";
        if (!StringUtils.hasText(extensionType)
                || (StringUtils.hasText(declared) && !"application/octet-stream".equals(declared)
                    && !detected.contentType().equals(declared))
                || !detected.contentType().equals(extensionType)) {
            validation("VIETQR_RECEIPT_IMAGE_TYPE_MISMATCH");
        }
    }

    private String assetId(String evidenceRef) {
        String value = evidenceRef == null ? "" : evidenceRef.trim();
        if (!value.matches("media:vqr_[0-9a-f]{32}")) {
            validation("VIETQR_RECEIPT_UPLOAD_EVIDENCE_REQUIRED");
        }
        return value.substring("media:".length());
    }

    private String requireIdempotencyKey(String value) {
        if (!StringUtils.hasText(value)) {
            throw new BizException(
                    OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(),
                    OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        return value.trim();
    }

    private byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new BizException(500, "UPLOAD_STREAM_READ_FAILED");
        }
    }

    private String sha256(byte[] bytes, String filename, String contentType) {
        MessageDigest digest = digest();
        digest.update(bytes);
        digest.update((byte) 0);
        digest.update(String.valueOf(filename).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(contentType.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }

    private String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    private MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new BizException(500, "SHA256_UNAVAILABLE");
        }
    }

    private int unsigned(byte value) {
        return value & 0xff;
    }

    private String actor() {
        String value = AdminActorResolver.resolve("authenticated-finance-admin");
        return StringUtils.hasText(value) ? value.trim() : "authenticated-finance-admin";
    }

    private void validation(String message) {
        throw new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), message);
    }

    private record DetectedImage(String contentType, String extension) {
    }
}
