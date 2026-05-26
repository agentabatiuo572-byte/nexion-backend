package ffdd.common.storage;

import ffdd.common.exception.BizException;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import java.io.InputStream;
import java.time.Duration;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ObjectStorageService {
    private static final int DEFAULT_EXPIRY_SECONDS = 900;
    private static final int MAX_EXPIRY_SECONDS = 86_400;

    private final MinioClient minioClient;
    private final StorageProperties properties;
    private volatile boolean bucketReady;

    public ObjectStorageService(MinioClient minioClient, StorageProperties properties) {
        this.minioClient = minioClient;
        this.properties = properties;
    }

    public StoredObject put(String objectKey, String contentType, InputStream inputStream, long sizeBytes) {
        validateObjectKey(objectKey);
        if (inputStream == null) {
            throw new BizException("Object input stream is required");
        }
        if (sizeBytes <= 0) {
            throw new BizException("Object size must be positive");
        }
        String normalizedContentType = normalizeContentType(contentType);
        try {
            ensureBucket();
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket())
                    .object(objectKey.trim())
                    .contentType(normalizedContentType)
                    .stream(inputStream, sizeBytes, -1)
                    .build());
            return new StoredObject(bucket(), objectKey.trim(), normalizedContentType, sizeBytes);
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException(500, "Object storage upload failed");
        }
    }

    public String presignGet(String objectKey, Duration expiry) {
        return presign(objectKey, Method.GET, expiry);
    }

    public String presignPut(String objectKey, String contentType, Duration expiry) {
        normalizeContentType(contentType);
        return presign(objectKey, Method.PUT, expiry);
    }

    private String presign(String objectKey, Method method, Duration expiry) {
        validateObjectKey(objectKey);
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .bucket(bucket())
                    .object(objectKey.trim())
                    .method(method)
                    .expiry(expirySeconds(expiry))
                    .build());
        } catch (Exception ex) {
            throw new BizException(500, "Object storage presign failed");
        }
    }

    private void ensureBucket() throws Exception {
        if (bucketReady) {
            return;
        }
        synchronized (this) {
            if (bucketReady) {
                return;
            }
            ensureBucketExists();
            bucketReady = true;
        }
    }

    private void ensureBucketExists() throws Exception {
        String bucket = bucket();
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder()
                .bucket(bucket)
                .build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder()
                    .bucket(bucket)
                    .build());
        }
    }

    private String bucket() {
        String bucket = properties.getBucket();
        if (!StringUtils.hasText(bucket)) {
            throw new BizException("Object storage bucket is required");
        }
        return bucket.trim();
    }

    private int expirySeconds(Duration expiry) {
        if (expiry == null || expiry.isZero() || expiry.isNegative()) {
            return DEFAULT_EXPIRY_SECONDS;
        }
        long seconds = expiry.toSeconds();
        if (seconds < 1) {
            return DEFAULT_EXPIRY_SECONDS;
        }
        return (int) Math.min(seconds, MAX_EXPIRY_SECONDS);
    }

    private String normalizeContentType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return "application/octet-stream";
        }
        String normalized = contentType.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 128 || normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0) {
            throw new BizException("Content type contains invalid characters");
        }
        return normalized;
    }

    private void validateObjectKey(String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            throw new BizException("Object key is required");
        }
        String trimmed = objectKey.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (trimmed.length() > 255
                || trimmed.startsWith("/")
                || trimmed.endsWith("/")
                || lower.startsWith("http://")
                || lower.startsWith("https://")
                || lower.contains("..")
                || trimmed.indexOf('\\') >= 0
                || trimmed.indexOf('\n') >= 0
                || trimmed.indexOf('\r') >= 0
                || trimmed.indexOf('\t') >= 0) {
            throw new BizException("Object key must be a storage object key");
        }
    }
}
