package ffdd.commerce.service;

import ffdd.commerce.dto.ProductMediaUploadResponse;
import ffdd.common.exception.BizException;
import ffdd.common.storage.ObjectStorageService;
import ffdd.common.storage.StoredObject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ProductMediaService {
    private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9._-]+");
    private static final DateTimeFormatter KEY_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Set<String> ALLOWED_MEDIA_TYPES = Set.of("COVER", "DETAIL", "PRODUCT_REVIEW", "GENESIS_COVER", "GENESIS_MEDIA", "USER_AVATAR");
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp",
            "video/mp4",
            "video/webm",
            "video/quicktime");
    private static final Map<String, String> DEFAULT_EXTENSIONS = Map.of(
            "image/jpeg", ".jpg",
            "image/png", ".png",
            "image/webp", ".webp",
            "video/mp4", ".mp4",
            "video/webm", ".webm",
            "video/quicktime", ".mov");
    private static final Map<String, Set<String>> ALLOWED_EXTENSIONS = Map.of(
            "image/jpeg", Set.of(".jpg", ".jpeg"),
            "image/png", Set.of(".png"),
            "image/webp", Set.of(".webp"),
            "video/mp4", Set.of(".mp4", ".m4v"),
            "video/webm", Set.of(".webm"),
            "video/quicktime", Set.of(".mov", ".qt"));

    private final ObjectStorageService storageService;
    private final long maxUploadSizeBytes;
    private final int previewExpirySeconds;

    public ProductMediaService(
            ObjectStorageService storageService,
            @Value("${nexion.commerce.product-media.max-upload-size-bytes:52428800}") long maxUploadSizeBytes,
            @Value("${nexion.commerce.product-media.preview-expiry-seconds:900}") int previewExpirySeconds) {
        this.storageService = storageService;
        this.maxUploadSizeBytes = Math.max(1, maxUploadSizeBytes);
        this.previewExpirySeconds = Math.max(60, Math.min(previewExpirySeconds, 86_400));
    }

    public ProductMediaUploadResponse upload(String mediaType, MultipartFile file) {
        String normalizedMediaType = normalizeMediaType(mediaType);
        FilePayload payload = readAndValidateFile(file);
        String objectKey = objectKey(normalizedMediaType, payload.extension());
        StoredObject storedObject = storageService.put(
                objectKey,
                payload.contentType(),
                new ByteArrayInputStream(payload.bytes()),
                payload.sizeBytes());
        ProductMediaUploadResponse response = new ProductMediaUploadResponse();
        response.setBucket(storedObject.getBucket());
        response.setObjectKey(storedObject.getObjectKey());
        response.setContentType(storedObject.getContentType());
        response.setSizeBytes(storedObject.getSizeBytes());
        response.setDownloadUrl(storageService.presignGet(storedObject.getObjectKey(), Duration.ofSeconds(previewExpirySeconds)));
        return response;
    }

    public ProductMediaUploadResponse preview(String objectKey) {
        validateStorageKey(objectKey);
        ProductMediaUploadResponse response = new ProductMediaUploadResponse();
        response.setObjectKey(objectKey.trim());
        response.setDownloadUrl(storageService.presignGet(objectKey.trim(), Duration.ofSeconds(previewExpirySeconds)));
        response.setContentType("application/octet-stream");
        return response;
    }

    private FilePayload readAndValidateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("Product media is required");
        }
        try {
            byte[] bytes = file.getBytes();
            FileDescriptor descriptor = fileDescriptor(file.getOriginalFilename(), file.getContentType(), (long) bytes.length);
            return new FilePayload(descriptor.contentType(), descriptor.extension(), descriptor.sizeBytes(), bytes);
        } catch (IOException ex) {
            throw new BizException("Product media cannot be read");
        }
    }

    private FileDescriptor fileDescriptor(String fileName, String contentType, Long sizeBytes) {
        if (sizeBytes == null || sizeBytes <= 0) {
            throw new BizException("Product media size must be positive");
        }
        if (sizeBytes > maxUploadSizeBytes) {
            throw new BizException("Product media is too large");
        }
        String normalizedContentType = normalizeContentType(contentType);
        if (!ALLOWED_CONTENT_TYPES.contains(normalizedContentType)) {
            throw new BizException("Unsupported product media content type");
        }
        return new FileDescriptor(
                normalizedContentType,
                safeExtension(safeFileName(fileName), normalizedContentType),
                sizeBytes);
    }

    private String objectKey(String mediaType, String extension) {
        String fileId = KEY_TIME_FORMAT.format(LocalDateTime.now())
                + "-"
                + UUID.randomUUID().toString().replace("-", "");
        if ("GENESIS_COVER".equals(mediaType)) {
            return "commerce/genesis/cover/" + fileId + extension;
        }
        if ("GENESIS_MEDIA".equals(mediaType)) {
            return "commerce/genesis/media/" + fileId + extension;
        }
        if ("USER_AVATAR".equals(mediaType)) {
            return "auth/users/avatar/" + fileId + extension;
        }
        return "commerce/products/" + mediaType.toLowerCase(Locale.ROOT) + "/" + fileId + extension;
    }

    private String normalizeMediaType(String mediaType) {
        if (!StringUtils.hasText(mediaType)) {
            return "DETAIL";
        }
        String normalized = mediaType.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_MEDIA_TYPES.contains(normalized)) {
            throw new BizException("Unsupported product media type");
        }
        return normalized;
    }

    private String normalizeContentType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            throw new BizException("Product media content type is required");
        }
        String normalized = contentType.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 128 || containsControlCharacters(normalized)) {
            throw new BizException("Product media content type contains invalid characters");
        }
        return normalized;
    }

    private String safeFileName(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return "product-image";
        }
        String normalized = fileName.trim().replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) {
            normalized = normalized.substring(slash + 1);
        }
        if (!StringUtils.hasText(normalized) || normalized.length() > 255 || containsControlCharacters(normalized)) {
            throw new BizException("Product media file name contains invalid characters");
        }
        return normalized;
    }

    private String safeExtension(String fileName, String contentType) {
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0 && dot < fileName.length() - 1) {
            String extension = fileName.substring(dot).toLowerCase(Locale.ROOT);
            if (extension.length() <= 16
                    && extension.matches("\\.[a-z0-9]+")
                    && ALLOWED_EXTENSIONS.getOrDefault(contentType, Set.of()).contains(extension)) {
                return extension;
            }
            throw new BizException("Unsupported product media file extension");
        }
        return DEFAULT_EXTENSIONS.getOrDefault(contentType, ".bin");
    }

    private void validateStorageKey(String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            throw new BizException("Object key is required");
        }
        String trimmed = objectKey.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (trimmed.length() > 255
                || !isAllowedObjectKeyPrefix(trimmed)
                || trimmed.startsWith("/")
                || trimmed.endsWith("/")
                || lower.startsWith("http://")
                || lower.startsWith("https://")
                || lower.contains("..")
                || trimmed.indexOf('\\') >= 0
                || containsControlCharacters(trimmed)
                || !SAFE_TOKEN.matcher(trimmed.substring(trimmed.lastIndexOf('/') + 1)).matches()) {
            throw new BizException("Object key must be an allowed media object key");
        }
    }

    private boolean isAllowedObjectKeyPrefix(String objectKey) {
        return objectKey.startsWith("commerce/products/")
                || objectKey.startsWith("commerce/genesis/")
                || objectKey.startsWith("auth/users/avatar/");
    }

    private boolean containsControlCharacters(String value) {
        return value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\t') >= 0;
    }

    private record FileDescriptor(String contentType, String extension, long sizeBytes) {
    }

    private record FilePayload(String contentType, String extension, long sizeBytes, byte[] bytes) {
    }
}
