package ffdd.opsconsole.media.application;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.media.dto.UploadedAsset;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
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
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@ApplicationService
@RequiredArgsConstructor
public class OpsMediaUploadService {
    private static final Duration PREVIEW_EXPIRY = Duration.ofMinutes(15);
    private static final String IDEMPOTENCY_SCOPE = "ADMIN_MEDIA_UPLOAD";
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
    private static final long MAX_VIDEO_BYTES = 200L * 1024 * 1024;
    private static final Pattern SEGMENT_PATTERN = Pattern.compile("[^a-z0-9-]");
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif");
    private static final Set<String> VIDEO_TYPES = Set.of("video/mp4", "video/webm", "video/quicktime");

    private final ObjectStorageService storageService;
    private final AuditLogService auditLogService;
    private final AdminIdempotencyService idempotencyService;
    private final Clock clock;

    public UploadedAsset upload(
            MultipartFile file,
            String idempotencyKey,
            String domain,
            String usage,
            String entityType,
            String entityId,
            String operator) {
        String normalizedIdempotencyKey = requireIdempotencyKey(idempotencyKey);
        if (file == null || file.isEmpty()) {
            throw new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "UPLOAD_FILE_REQUIRED");
        }
        String contentType = normalizeContentType(file);
        validateMediaType(contentType, file.getSize());
        String normalizedDomain = segment(domain, "shared");
        String normalizedUsage = segment(usage, "general");
        byte[] bytes = fileBytes(file);
        String requestHash = requestHash(
                file.getOriginalFilename(),
                contentType,
                bytes,
                normalizedDomain,
                normalizedUsage,
                entityType,
                entityId,
                operator);
        return idempotencyService.execute(
                IDEMPOTENCY_SCOPE,
                normalizedIdempotencyKey,
                requestHash,
                UploadedAsset.class,
                () -> uploadNewAsset(bytes, file.getOriginalFilename(), contentType, normalizedDomain, normalizedUsage,
                        normalizedIdempotencyKey, entityType, entityId, operator));
    }

    private UploadedAsset uploadNewAsset(
            byte[] bytes,
            String filename,
            String contentType,
            String normalizedDomain,
            String normalizedUsage,
            String normalizedIdempotencyKey,
            String entityType,
            String entityId,
            String operator) {
        String objectKey = objectKey(normalizedDomain, normalizedUsage, filename, contentType);
        StoredObject storedObject = storageService.put(
                objectKey,
                contentType,
                new ByteArrayInputStream(bytes),
                bytes.length);
        String previewUrl = storageService.presignGet(storedObject.getObjectKey(), PREVIEW_EXPIRY);
        UploadedAsset asset = asset(storedObject, previewUrl, normalizedDomain, normalizedUsage);
        audit(asset, normalizedIdempotencyKey, entityType, entityId, operator);
        return asset;
    }

    public UploadedAsset refreshPreviewUrl(String assetId) {
        String objectKey = decodeAssetId(assetId);
        String previewUrl = storageService.presignGet(objectKey, PREVIEW_EXPIRY);
        return new UploadedAsset(
                assetId,
                objectKey,
                null,
                null,
                null,
                previewUrl,
                LocalDateTime.now(clock).plus(PREVIEW_EXPIRY),
                null,
                null);
    }

    private UploadedAsset asset(StoredObject storedObject, String previewUrl, String domain, String usage) {
        return new UploadedAsset(
                encodeAssetId(storedObject.getObjectKey()),
                storedObject.getObjectKey(),
                storedObject.getBucket(),
                storedObject.getContentType(),
                storedObject.getSizeBytes(),
                previewUrl,
                LocalDateTime.now(clock).plus(PREVIEW_EXPIRY),
                domain,
                usage);
    }

    private String normalizeContentType(MultipartFile file) {
        String contentType = file.getContentType();
        if (StringUtils.hasText(contentType)) {
            return contentType.trim().toLowerCase(Locale.ROOT);
        }
        return contentTypeFromFilename(file.getOriginalFilename());
    }

    private String contentTypeFromFilename(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".mp4")) {
            return "video/mp4";
        }
        if (lower.endsWith(".webm")) {
            return "video/webm";
        }
        if (lower.endsWith(".mov")) {
            return "video/quicktime";
        }
        return "application/octet-stream";
    }

    private void validateMediaType(String contentType, long sizeBytes) {
        if (IMAGE_TYPES.contains(contentType)) {
            if (sizeBytes > MAX_IMAGE_BYTES) {
                throw new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "IMAGE_UPLOAD_TOO_LARGE");
            }
            return;
        }
        if (VIDEO_TYPES.contains(contentType)) {
            if (sizeBytes > MAX_VIDEO_BYTES) {
                throw new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "VIDEO_UPLOAD_TOO_LARGE");
            }
            return;
        }
        throw new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "UNSUPPORTED_MEDIA_TYPE");
    }

    private String objectKey(String domain, String usage, String filename, String contentType) {
        LocalDateTime now = LocalDateTime.now(clock);
        return "admin/%s/%s/%04d%02d%02d/%s.%s".formatted(
                domain,
                usage,
                now.getYear(),
                now.getMonthValue(),
                now.getDayOfMonth(),
                UUID.randomUUID(),
                extension(filename, contentType));
    }

    private String extension(String filename, String contentType) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "jpg";
        }
        if (lower.endsWith(".png")) {
            return "png";
        }
        if (lower.endsWith(".webp")) {
            return "webp";
        }
        if (lower.endsWith(".gif")) {
            return "gif";
        }
        if (lower.endsWith(".mp4")) {
            return "mp4";
        }
        if (lower.endsWith(".webm")) {
            return "webm";
        }
        if (lower.endsWith(".mov")) {
            return "mov";
        }
        return switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            case "video/mp4" -> "mp4";
            case "video/webm" -> "webm";
            case "video/quicktime" -> "mov";
            default -> "bin";
        };
    }

    private String segment(String value, String fallback) {
        String normalized = StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : fallback;
        normalized = SEGMENT_PATTERN.matcher(normalized.replace("_", "-")).replaceAll("-");
        normalized = normalized.replaceAll("-+", "-").replaceAll("^-|-$", "");
        return StringUtils.hasText(normalized) ? normalized : fallback;
    }

    private String requireIdempotencyKey(String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            throw new BizException(
                    OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(),
                    OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        return idempotencyKey.trim();
    }

    private byte[] fileBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new BizException(500, "UPLOAD_STREAM_READ_FAILED");
        }
    }

    private String requestHash(
            String filename,
            String contentType,
            byte[] bytes,
            String domain,
            String usage,
            String entityType,
            String entityId,
            String operator) {
        MessageDigest digest = sha256();
        updateDigest(digest, text(filename));
        updateDigest(digest, text(contentType));
        updateDigest(digest, String.valueOf(bytes.length));
        digest.update(bytes);
        updateDigest(digest, text(domain));
        updateDigest(digest, text(usage));
        updateDigest(digest, text(entityType));
        updateDigest(digest, text(entityId));
        updateDigest(digest, text(operator));
        return HexFormat.of().formatHex(digest.digest());
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new BizException(500, "SHA256_UNAVAILABLE");
        }
    }

    private void updateDigest(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private String text(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    private String encodeAssetId(String objectKey) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(objectKey.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeAssetId(String assetId) {
        if (!StringUtils.hasText(assetId)) {
            throw new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "ASSET_ID_REQUIRED");
        }
        try {
            return new String(Base64.getUrlDecoder().decode(assetId.trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "ASSET_ID_INVALID");
        }
    }

    private void audit(UploadedAsset asset, String idempotencyKey, String entityType, String entityId, String operator) {
        auditLogService.record(AuditLogWriteRequest.builder()
                .action("ADMIN_MEDIA_UPLOADED")
                .resourceType(StringUtils.hasText(entityType) ? entityType.trim() : "MEDIA_ASSET")
                .resourceId(StringUtils.hasText(entityId) ? entityId.trim() : asset.assetId())
                .actorType("ADMIN")
                .actorUsername(StringUtils.hasText(operator) ? operator.trim() : null)
                .result("SUCCESS")
                .riskLevel("MEDIUM")
                .detail(Map.of(
                        "assetId", asset.assetId(),
                        "objectKey", asset.objectKey(),
                        "bucket", asset.bucket(),
                        "contentType", asset.contentType(),
                        "sizeBytes", asset.sizeBytes(),
                        "domain", asset.domain(),
                        "usage", asset.usage(),
                        "idempotencyKey", idempotencyKey))
                .build());
    }
}
