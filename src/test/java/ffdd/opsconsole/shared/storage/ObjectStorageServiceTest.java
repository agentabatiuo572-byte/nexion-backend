package ffdd.opsconsole.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.exception.BizException;
import io.minio.MinioClient;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ObjectStorageServiceTest {
    private final MinioClient minioClient = mock(MinioClient.class);
    private final StorageProperties properties = new StorageProperties();
    private final ObjectStorageService service = new ObjectStorageService(minioClient, properties);

    @Test
    void rejectsUnsafeObjectKeyBeforeCallingStorage() {
        assertThatThrownBy(() -> service.presignGet("../documents/passport.jpg", Duration.ofMinutes(5)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Object key must be a storage object key");
    }

    @Test
    void createsPresignedGetUrl() throws Exception {
        properties.setBucket("nexion-private");
        when(minioClient.getPresignedObjectUrl(any())).thenReturn("http://minio.local/nexion-private/documents/doc.jpg");

        String url = service.presignGet("documents/10001/doc.jpg", Duration.ofMinutes(10));

        assertThat(url).isEqualTo("http://minio.local/nexion-private/documents/doc.jpg");
    }

    @Test
    void verifiesThatManagedObjectExists() throws Exception {
        properties.setBucket("nexion-private");
        when(minioClient.statObject(any())).thenReturn(mock(StatObjectResponse.class));

        assertThat(service.exists("admin/e/sku-image/20260903/asset.png")).isTrue();
    }

    @Test
    void reportsMissingManagedObjectWithoutTreatingItAsPresent() throws Exception {
        properties.setBucket("nexion-private");
        ErrorResponseException missing = mock(ErrorResponseException.class);
        ErrorResponse response = mock(ErrorResponse.class);
        when(missing.errorResponse()).thenReturn(response);
        when(response.code()).thenReturn("NoSuchKey");
        when(minioClient.statObject(any())).thenThrow(missing);

        assertThat(service.exists("admin/e/sku-image/20260903/missing.png")).isFalse();
    }
}
