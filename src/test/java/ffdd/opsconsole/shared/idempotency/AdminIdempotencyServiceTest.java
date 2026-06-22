package ffdd.opsconsole.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AdminIdempotencyServiceTest {
    private final AdminIdempotencyRecordMapper recordMapper = org.mockito.Mockito.mock(AdminIdempotencyRecordMapper.class);
    private final AdminIdempotencyService service = new AdminIdempotencyService(
            recordMapper,
            new ObjectMapper().findAndRegisterModules(),
            Clock.fixed(Instant.parse("2026-06-18T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void storesFirstSuccessfulResponse() {
        when(recordMapper.selectActive("ADMIN_MEDIA_UPLOAD", "idem-1")).thenReturn(null);
        when(recordMapper.insert(any(AdminIdempotencyRecordEntity.class))).thenAnswer(invocation -> {
            AdminIdempotencyRecordEntity entity = invocation.getArgument(0);
            entity.setId(9L);
            return 1;
        });

        Map<?, ?> result = service.execute(
                "admin_media_upload",
                "idem-1",
                "hash-a",
                Map.class,
                () -> Map.of("assetId", "asset-1"));

        assertThat(result.get("assetId")).isEqualTo("asset-1");
        ArgumentCaptor<AdminIdempotencyRecordEntity> captor =
                ArgumentCaptor.forClass(AdminIdempotencyRecordEntity.class);
        verify(recordMapper).insert(captor.capture());
        assertThat(captor.getValue().getScope()).isEqualTo("ADMIN_MEDIA_UPLOAD");
        assertThat(captor.getValue().getRequestHash()).isEqualTo("hash-a");
        verify(recordMapper).markSucceeded(eq(9L), org.mockito.ArgumentMatchers.contains("asset-1"));
    }

    @Test
    void replaysStoredResponseForSameRequestHash() {
        AdminIdempotencyRecordEntity existing = existing("hash-a", "SUCCEEDED", "{\"assetId\":\"asset-1\"}");
        when(recordMapper.selectActive("ADMIN_MEDIA_UPLOAD", "idem-1")).thenReturn(existing);
        AtomicBoolean called = new AtomicBoolean(false);

        Map<?, ?> result = service.execute(
                "ADMIN_MEDIA_UPLOAD",
                "idem-1",
                "hash-a",
                Map.class,
                () -> {
                    called.set(true);
                    return Map.of("assetId", "asset-2");
                });

        assertThat(result.get("assetId")).isEqualTo("asset-1");
        assertThat(called).isFalse();
        verify(recordMapper, never()).insert(any(AdminIdempotencyRecordEntity.class));
    }

    @Test
    void rejectsSameKeyWithDifferentRequestHash() {
        AdminIdempotencyRecordEntity existing = existing("hash-a", "SUCCEEDED", "{\"assetId\":\"asset-1\"}");
        when(recordMapper.selectActive("ADMIN_MEDIA_UPLOAD", "idem-1")).thenReturn(existing);

        assertThatThrownBy(() -> service.execute(
                "ADMIN_MEDIA_UPLOAD",
                "idem-1",
                "hash-b",
                Map.class,
                () -> Map.of("assetId", "asset-2")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH")
                .extracting("code")
                .isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    private AdminIdempotencyRecordEntity existing(String requestHash, String status, String responseJson) {
        AdminIdempotencyRecordEntity entity = new AdminIdempotencyRecordEntity();
        entity.setId(9L);
        entity.setScope("ADMIN_MEDIA_UPLOAD");
        entity.setIdempotencyKey("idem-1");
        entity.setRequestHash(requestHash);
        entity.setStatus(status);
        entity.setResponseJson(responseJson);
        return entity;
    }
}
