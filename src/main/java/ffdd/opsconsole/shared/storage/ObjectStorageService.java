package ffdd.opsconsole.shared.storage;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.shared.exception.BizException;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import java.io.InputStream;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ObjectStorageService {
    private static final Logger log = LoggerFactory.getLogger(ObjectStorageService.class);
    private static final int DEFAULT_EXPIRY_SECONDS = 900;
    private static final int MAX_EXPIRY_SECONDS = 86_400;

    private final MinioClient minioClient;
    private final StorageProperties properties;
    private final AtomicBoolean bucketReady = new AtomicBoolean(false);

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
            log.warn("Object storage upload failed, endpoint={}, bucket={}, objectKey={}, contentType={}, sizeBytes={}, errorType={}, error={}",
                    properties.getEndpoint(), bucket(), objectKey, normalizedContentType, sizeBytes,
                    ex.getClass().getName(), ex.getMessage(), ex);
            throw new BizException(500, storageFailureMessage("上传", ex));
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
            log.warn("Object storage presign failed, endpoint={}, bucket={}, objectKey={}, method={}, errorType={}, error={}",
                    properties.getEndpoint(), bucket(), objectKey, method,
                    ex.getClass().getName(), ex.getMessage(), ex);
            throw new BizException(500, storageFailureMessage("预览链接生成", ex));
        }
    }

    private String storageFailureMessage(String action, Exception ex) {
        String error = (ex.getClass().getSimpleName() + " " + String.valueOf(ex.getMessage())).toLowerCase(Locale.ROOT);
        if (error.contains("access denied")
                || error.contains("invalidaccesskeyid")
                || error.contains("signaturedoesnotmatch")
                || error.contains("credentials")) {
            return "MinIO " + action + "失败：账号或密码配置不正确";
        }
        if (error.contains("connection refused")
                || error.contains("connect timed out")
                || error.contains("no route to host")
                || error.contains("unknownhost")
                || error.contains("failed to connect")) {
            return "MinIO " + action + "失败：对象存储服务不可连接";
        }
        if (error.contains("bucket")
                || error.contains("nosuchbucket")
                || error.contains("invalidbucketname")) {
            return "MinIO " + action + "失败：bucket 配置或创建失败";
        }
        return "MinIO " + action + "失败，请查看后端日志中的对象存储错误";
    }

    private void ensureBucket() throws Exception {
        if (bucketReady.get()) {
            return;
        }
        synchronized (this) {
            if (bucketReady.get()) {
                return;
            }
            ensureBucketExists();
            bucketReady.set(true);
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
