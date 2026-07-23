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
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class AdminIdempotencyServiceTest {
    private final AdminIdempotencyRecordMapper recordMapper = org.mockito.Mockito.mock(AdminIdempotencyRecordMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AdminIdempotencyTransactionExecutor transactionExecutor =
            new AdminIdempotencyTransactionExecutor(recordMapper, objectMapper);
    private final AdminIdempotencyService service = new AdminIdempotencyService(
            transactionExecutor,
            Clock.fixed(Instant.parse("2026-06-18T00:00:00Z"), ZoneOffset.UTC));

    @BeforeEach
    void markSucceededNormallyUpdatesOneRow() {
        when(recordMapper.markSucceeded(any(), any())).thenReturn(1);
        when(recordMapper.markFailed(any(), any())).thenReturn(1);
    }

    @Test
    void storesFirstSuccessfulResponse() {
        when(recordMapper.selectActive("ADMIN_MEDIA_UPLOAD", "idem-1")).thenReturn(null);
        when(recordMapper.selectCurrent("ADMIN_MEDIA_UPLOAD", "idem-1")).thenReturn(null);
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
        verify(recordMapper, never()).resetExpiredById(any(), any(), any());
        assertThat(captor.getValue().getScope()).isEqualTo("ADMIN_MEDIA_UPLOAD");
        assertThat(captor.getValue().getRequestHash()).isEqualTo("hash-a");
        verify(recordMapper).markSucceeded(eq(9L), org.mockito.ArgumentMatchers.contains("asset-1"));
    }

    @Test
    void expirationTimestampIsTruncatedToDatabaseSecondPrecision() {
        Clock subSecondClock = Clock.fixed(
                Instant.parse("2026-06-18T00:00:00.900Z"),
                ZoneOffset.ofHours(8));
        AdminIdempotencyService preciseService = new AdminIdempotencyService(
                transactionExecutor,
                subSecondClock);
        when(recordMapper.selectActive("D1_CLOCK_PRECISION", "idem-clock")).thenReturn(null);
        when(recordMapper.selectCurrent("D1_CLOCK_PRECISION", "idem-clock")).thenReturn(null);
        when(recordMapper.insert(any(AdminIdempotencyRecordEntity.class))).thenAnswer(invocation -> {
            AdminIdempotencyRecordEntity entity = invocation.getArgument(0);
            entity.setId(10L);
            return 1;
        });

        preciseService.execute(
                "D1_CLOCK_PRECISION",
                "idem-clock",
                "hash-clock",
                Map.class,
                () -> Map.of("ok", true));

        ArgumentCaptor<AdminIdempotencyRecordEntity> captor =
                ArgumentCaptor.forClass(AdminIdempotencyRecordEntity.class);
        verify(recordMapper).insert(captor.capture());
        assertThat(captor.getValue().getExpiresAt())
                .isEqualTo(LocalDateTime.of(2026, 6, 19, 8, 0));
        assertThat(captor.getValue().getExpiresAt().getNano()).isZero();
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

    @Test
    void reclaimsAnExpiredKeyInsteadOfLeavingItPermanentlyConflicted() {
        AdminIdempotencyRecordEntity expired = existing("hash-old", "SUCCEEDED", "{\"assetId\":\"asset-old\"}");
        expired.setExpiresAt(LocalDateTime.of(2026, 6, 17, 0, 0));
        when(recordMapper.selectActive("ADMIN_MEDIA_UPLOAD", "idem-1")).thenReturn(null);
        when(recordMapper.selectCurrent("ADMIN_MEDIA_UPLOAD", "idem-1")).thenReturn(expired);
        when(recordMapper.resetExpiredById(
                eq(9L), eq("hash-new"), any(LocalDateTime.class)))
                .thenReturn(1);

        Map<?, ?> result = service.execute(
                "ADMIN_MEDIA_UPLOAD",
                "idem-1",
                "hash-new",
                Map.class,
                () -> Map.of("assetId", "asset-new"));

        assertThat(result.get("assetId")).isEqualTo("asset-new");
        verify(recordMapper, never()).insert(any(AdminIdempotencyRecordEntity.class));
        verify(recordMapper).markSucceeded(eq(9L), org.mockito.ArgumentMatchers.contains("asset-new"));
    }

    @Test
    void rejectsKeyLongerThanDatabaseLimitBeforeTouchingTheMapper() {
        String oversizedKey = "k".repeat(129);

        assertThatThrownBy(() -> service.execute(
                "ADMIN_MEDIA_UPLOAD",
                oversizedKey,
                "hash-a",
                Map.class,
                () -> Map.of("assetId", "asset-1")))
                .isInstanceOf(BizException.class)
                .hasMessage("IDEMPOTENCY_KEY_INVALID")
                .extracting("code")
                .isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());

        verify(recordMapper, never()).selectActive(any(), any());
        verify(recordMapper, never()).insert(any(AdminIdempotencyRecordEntity.class));
    }

    @Test
    void truncatesFailureMetadataWithoutMaskingTheOriginalException() {
        when(recordMapper.selectActive("ADMIN_MEDIA_UPLOAD", "idem-long-error")).thenReturn(null);
        when(recordMapper.selectCurrent("ADMIN_MEDIA_UPLOAD", "idem-long-error")).thenReturn(null);
        when(recordMapper.insert(any(AdminIdempotencyRecordEntity.class))).thenAnswer(invocation -> {
            AdminIdempotencyRecordEntity entity = invocation.getArgument(0);
            entity.setId(10L);
            return 1;
        });
        IllegalStateException original = new IllegalStateException("x".repeat(1200));

        assertThatThrownBy(() -> service.execute(
                "ADMIN_MEDIA_UPLOAD",
                "idem-long-error",
                "hash-error",
                Map.class,
                () -> { throw original; }))
                .isSameAs(original);

        ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
        verify(recordMapper).markFailed(eq(10L), errorCaptor.capture());
        assertThat(errorCaptor.getValue()).hasSize(512).endsWith("…");
    }

    @Test
    void retriesAPreviouslyFailedRequestWithTheSameKeyAndPayload() {
        AdminIdempotencyRecordEntity failed = existing("hash-a", "FAILED", null);
        when(recordMapper.selectActive("ADMIN_MEDIA_UPLOAD", "idem-retry")).thenReturn(failed);
        when(recordMapper.resetFailedById(eq(9L), eq("hash-a"), any(LocalDateTime.class))).thenReturn(1);

        Map<?, ?> result = service.execute(
                "ADMIN_MEDIA_UPLOAD",
                "idem-retry",
                "hash-a",
                Map.class,
                () -> Map.of("assetId", "asset-retried"));

        assertThat(result.get("assetId")).isEqualTo("asset-retried");
        verify(recordMapper).markSucceeded(eq(9L), org.mockito.ArgumentMatchers.contains("asset-retried"));
    }

    @Test
    void failedActionIsDurablyRetryableInsteadOfRemainingProcessing() {
        AtomicReference<AdminIdempotencyRecordEntity> stored = new AtomicReference<>();
        AtomicInteger actionCalls = new AtomicInteger();
        when(recordMapper.selectActive("A2_COMMAND", "idem-denied")).thenAnswer(invocation -> stored.get());
        when(recordMapper.selectCurrent("A2_COMMAND", "idem-denied")).thenAnswer(invocation -> stored.get());
        when(recordMapper.insert(any(AdminIdempotencyRecordEntity.class))).thenAnswer(invocation -> {
            AdminIdempotencyRecordEntity entity = invocation.getArgument(0);
            entity.setId(12L);
            stored.set(entity);
            return 1;
        });
        when(recordMapper.markFailed(eq(12L), any())).thenAnswer(invocation -> {
            stored.get().setStatus("FAILED");
            stored.get().setErrorMessage(invocation.getArgument(1));
            return 1;
        });
        when(recordMapper.resetFailedById(eq(12L), eq("hash-denied"), any(LocalDateTime.class)))
                .thenAnswer(invocation -> {
                    stored.get().setStatus("PROCESSING");
                    return 1;
                });
        when(recordMapper.markSucceeded(eq(12L), any())).thenAnswer(invocation -> {
            stored.get().setStatus("SUCCEEDED");
            stored.get().setResponseJson(invocation.getArgument(1));
            return 1;
        });

        assertThatThrownBy(() -> service.execute(
                "A2_COMMAND", "idem-denied", "hash-denied", Map.class,
                () -> {
                    actionCalls.incrementAndGet();
                    throw new BizException(403, "A2_MAKER_CHECKER_REQUIRED");
                }))
                .isInstanceOf(BizException.class)
                .hasMessage("A2_MAKER_CHECKER_REQUIRED");
        assertThat(stored.get().getStatus()).isEqualTo("FAILED");

        Map<?, ?> retried = service.execute(
                "A2_COMMAND", "idem-denied", "hash-denied", Map.class,
                () -> {
                    actionCalls.incrementAndGet();
                    return Map.of("approved", true);
                });

        assertThat(retried.get("approved")).isEqualTo(true);
        assertThat(actionCalls).hasValue(2);
        assertThat(stored.get().getStatus()).isEqualTo("SUCCEEDED");
    }

    @Test
    void duplicateInsertRaceUsesCurrentReadAndReportsTheRequestInProgress() {
        when(recordMapper.selectActive("ADMIN_MEDIA_UPLOAD", "idem-race")).thenReturn(null);
        when(recordMapper.selectCurrent("ADMIN_MEDIA_UPLOAD", "idem-race")).thenReturn(null);
        when(recordMapper.insert(any(AdminIdempotencyRecordEntity.class)))
                .thenThrow(new DuplicateKeyException("concurrent winner"));
        AdminIdempotencyRecordEntity winner = existing("hash-a", "PROCESSING", null);
        winner.setIdempotencyKey("idem-race");
        when(recordMapper.selectActiveForUpdate("ADMIN_MEDIA_UPLOAD", "idem-race")).thenReturn(winner);

        assertThatThrownBy(() -> service.execute(
                "ADMIN_MEDIA_UPLOAD",
                "idem-race",
                "hash-a",
                Map.class,
                () -> Map.of("assetId", "must-not-run")))
                .isInstanceOf(BizException.class)
                .hasMessage("IDEMPOTENCY_REQUEST_IN_PROGRESS");

        verify(recordMapper).selectActiveForUpdate("ADMIN_MEDIA_UPLOAD", "idem-race");
        verify(recordMapper, never()).markSucceeded(any(), any());
    }

    @Test
    void successMarkerLossFailsClosedAndTransactionPhasesHaveExplicitBoundaries() throws Exception {
        when(recordMapper.selectActive("ADMIN_MEDIA_UPLOAD", "idem-marker-loss")).thenReturn(null);
        when(recordMapper.selectCurrent("ADMIN_MEDIA_UPLOAD", "idem-marker-loss")).thenReturn(null);
        when(recordMapper.insert(any(AdminIdempotencyRecordEntity.class))).thenAnswer(invocation -> {
            AdminIdempotencyRecordEntity entity = invocation.getArgument(0);
            entity.setId(11L);
            return 1;
        });
        when(recordMapper.markSucceeded(eq(11L), any())).thenReturn(0);

        assertThatThrownBy(() -> service.execute(
                "ADMIN_MEDIA_UPLOAD",
                "idem-marker-loss",
                "hash-marker-loss",
                Map.class,
                () -> Map.of("assetId", "must-roll-back")))
                .isInstanceOf(BizException.class)
                .hasMessage("IDEMPOTENCY_SUCCESS_STATE_LOST");

        assertThat(AdminIdempotencyService.class
                .getMethod("execute", String.class, String.class, String.class, Class.class, java.util.function.Supplier.class)
                .getAnnotation(Transactional.class)).isNull();
        assertThat(AdminIdempotencyTransactionExecutor.class
                .getMethod("claim", String.class, String.class, String.class, LocalDateTime.class, Class.class)
                .getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(AdminIdempotencyTransactionExecutor.class
                .getMethod("runClaimed", Long.class, java.util.function.Supplier.class)
                .getAnnotation(Transactional.class)).isNotNull();
        assertThat(AdminIdempotencyTransactionExecutor.class
                .getMethod("markFailed", Long.class, String.class)
                .getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.REQUIRES_NEW);
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
