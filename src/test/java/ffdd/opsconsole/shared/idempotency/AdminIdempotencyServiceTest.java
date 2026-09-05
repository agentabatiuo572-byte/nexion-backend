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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
    private final AdminIdempotencyExpiryTransitionExecutor expiryTransitionExecutor =
            org.mockito.Mockito.mock(AdminIdempotencyExpiryTransitionExecutor.class);
    private final AdminIdempotencyTransactionExecutor transactionExecutor =
            new AdminIdempotencyTransactionExecutor(recordMapper, objectMapper, expiryTransitionExecutor);
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
    void questClaimRejectsTheSameKeyWhenTheMissionInstanceChanges() {
        String scope = "APP:QUEST_CLAIM:USER:42";
        String key = "weekly-quest-key";
        AdminIdempotencyRecordEntity prior = existing(
                sha256("H3_DEVICE_ACTIVATED|WEEK:2026-W35"), "SUCCEEDED", "{\"status\":\"CLAIMED\"}");
        prior.setScope(scope);
        prior.setIdempotencyKey(key);
        when(recordMapper.selectActive(scope, key)).thenReturn(prior);
        AtomicBoolean actionCalled = new AtomicBoolean(false);

        assertThatThrownBy(() -> service.execute(
                scope, key, sha256("H3_DEVICE_ACTIVATED|WEEK:2026-W36"), Map.class,
                () -> {
                    actionCalled.set(true);
                    return Map.of("status", "CLAIMED");
                }))
                .isInstanceOf(BizException.class)
                .hasMessage("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");

        assertThat(actionCalled).isFalse();
        verify(recordMapper, never()).markSucceeded(any(), any());
    }

    @Test
    void retainedReplayKeepsAnExpiredSucceededFinancialReceiptOutOfTheActionPath() {
        AdminIdempotencyRecordEntity succeeded = existing("hash-g7", "SUCCEEDED", "{\"amount\":200}");
        succeeded.setExpiresAt(LocalDateTime.of(2026, 6, 17, 0, 0));
        when(recordMapper.selectActive("APP:G7_REPURCHASE_OPEN:USER:7", "g7-open-expired"))
                .thenReturn(null);
        when(recordMapper.selectCurrent("APP:G7_REPURCHASE_OPEN:USER:7", "g7-open-expired"))
                .thenReturn(succeeded);
        AtomicBoolean actionCalled = new AtomicBoolean(false);

        Map<?, ?> replayed = service.executeRetained(
                "APP:G7_REPURCHASE_OPEN:USER:7", "g7-open-expired", "hash-g7", Map.class,
                () -> {
                    actionCalled.set(true);
                    return Map.of("amount", 999);
                });

        assertThat(replayed.get("amount")).isEqualTo(200);
        assertThat(actionCalled).isFalse();
        verify(recordMapper, never()).resetExpiredById(any(), any(), any());
        verify(recordMapper, never()).insert(any(AdminIdempotencyRecordEntity.class));
    }

    @Test
    void retainedExpiredSuccessRejectsAnyPayloadMismatchWithoutExecutingAgain() {
        AdminIdempotencyRecordEntity succeeded = existing("hash-g7-original", "SUCCEEDED", "{\"amount\":200}");
        succeeded.setExpiresAt(LocalDateTime.of(2026, 6, 17, 0, 0));
        when(recordMapper.selectActive("APP:G7_REPURCHASE_OPEN:USER:7", "g7-open-mismatch"))
                .thenReturn(null);
        when(recordMapper.selectCurrent("APP:G7_REPURCHASE_OPEN:USER:7", "g7-open-mismatch"))
                .thenReturn(succeeded);
        AtomicBoolean actionCalled = new AtomicBoolean(false);

        assertThatThrownBy(() -> service.executeRetained(
                "APP:G7_REPURCHASE_OPEN:USER:7", "g7-open-mismatch", "hash-g7-rewritten", Map.class,
                () -> {
                    actionCalled.set(true);
                    return Map.of("amount", 999);
                }))
                .isInstanceOf(BizException.class)
                .hasMessage("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");

        assertThat(actionCalled).isFalse();
        verify(recordMapper, never()).resetExpiredById(any(), any(), any());
    }

    @Test
    void retainedFailedRequestRetriesOnlyThroughTheFailedStatusCas() {
        AdminIdempotencyRecordEntity failed = existing("hash-g7-failed", "FAILED", null);
        failed.setId(47L);
        failed.setExpiresAt(LocalDateTime.of(2026, 6, 17, 0, 0));
        when(recordMapper.selectActive("APP:G7_REPURCHASE_OPEN:USER:7", "g7-open-failed"))
                .thenReturn(null);
        when(recordMapper.selectCurrent("APP:G7_REPURCHASE_OPEN:USER:7", "g7-open-failed"))
                .thenReturn(failed);
        when(recordMapper.resetFailedById(eq(47L), eq("hash-g7-failed"), any(LocalDateTime.class)))
                .thenReturn(1);

        Map<?, ?> retried = service.executeRetained(
                "APP:G7_REPURCHASE_OPEN:USER:7", "g7-open-failed", "hash-g7-failed", Map.class,
                () -> Map.of("retried", true));

        assertThat(retried.get("retried")).isEqualTo(true);
        verify(recordMapper).resetFailedById(eq(47L), eq("hash-g7-failed"), any(LocalDateTime.class));
        verify(recordMapper, never()).resetExpiredById(any(), any(), any());
        verify(recordMapper).markSucceeded(eq(47L), org.mockito.ArgumentMatchers.contains("retried"));
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
    void persistsOnlyFailureTypeSoIdempotencyMetadataNeverLeaksRequestPayload() {
        when(recordMapper.selectActive("ADMIN_MEDIA_UPLOAD", "idem-long-error")).thenReturn(null);
        when(recordMapper.selectCurrent("ADMIN_MEDIA_UPLOAD", "idem-long-error")).thenReturn(null);
        when(recordMapper.insert(any(AdminIdempotencyRecordEntity.class))).thenAnswer(invocation -> {
            AdminIdempotencyRecordEntity entity = invocation.getArgument(0);
            entity.setId(10L);
            return 1;
        });
        IllegalStateException original = new IllegalStateException("payload=super-secret-authorization-value");

        assertThatThrownBy(() -> service.execute(
                "ADMIN_MEDIA_UPLOAD",
                "idem-long-error",
                "hash-error",
                Map.class,
                () -> { throw original; }))
                .isSameAs(original);

        ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
        verify(recordMapper).markFailed(eq(10L), errorCaptor.capture());
        assertThat(errorCaptor.getValue()).isEqualTo("IllegalStateException");
        assertThat(errorCaptor.getValue()).doesNotContain("super-secret");
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
    void expiredProcessingOutcomeIsTransitionedInAnIndependentBoundaryThenFailsClosed() {
        AdminIdempotencyRecordEntity unknown = existing("hash-unknown", "PROCESSING", null);
        unknown.setId(21L);
        unknown.setIsDeleted(0);
        unknown.setExpiresAt(LocalDateTime.of(2026, 6, 17, 0, 0));
        when(recordMapper.selectActive("J4_PLAYBOOK_EXECUTE:SOP-CUSTOM-1", "idem-unknown"))
                .thenReturn(null);
        when(recordMapper.selectCurrent("J4_PLAYBOOK_EXECUTE:SOP-CUSTOM-1", "idem-unknown"))
                .thenReturn(unknown);
        when(expiryTransitionExecutor.markCurrentExpiredProcessingUnknown(
                21L, "J4_PLAYBOOK_EXECUTE:SOP-CUSTOM-1", "idem-unknown"))
                .thenReturn(1);
        AtomicBoolean actionCalled = new AtomicBoolean(false);

        assertThatThrownBy(() -> service.execute(
                "J4_PLAYBOOK_EXECUTE:SOP-CUSTOM-1", "idem-unknown", "hash-unknown", Map.class,
                () -> {
                    actionCalled.set(true);
                    return Map.of("mustNot", "run");
                }))
                .isInstanceOf(BizException.class)
                .hasMessage("IDEMPOTENCY_RESULT_UNKNOWN");

        assertThat(actionCalled).isFalse();
        verify(expiryTransitionExecutor).markCurrentExpiredProcessingUnknown(
                21L, "J4_PLAYBOOK_EXECUTE:SOP-CUSTOM-1", "idem-unknown");
        verify(recordMapper, never()).resetExpiredById(any(), any(), any());
        verify(recordMapper, never()).insert(any(AdminIdempotencyRecordEntity.class));
    }

    @Test
    void exactExpiryCasMissReloadsTheActualDatabaseStateInsteadOfAssumingUnknown() {
        AdminIdempotencyRecordEntity stale = existing("hash-a", "PROCESSING", null);
        stale.setId(22L);
        stale.setIsDeleted(0);
        stale.setExpiresAt(LocalDateTime.of(2026, 6, 17, 0, 0));
        AdminIdempotencyRecordEntity active = existing("hash-a", "PROCESSING", null);
        active.setId(22L);
        active.setIsDeleted(0);
        when(recordMapper.selectActive("J4_PLAYBOOK_EXECUTE:SOP-CUSTOM-1", "idem-cas-race"))
                .thenReturn(null, active);
        when(recordMapper.selectCurrent("J4_PLAYBOOK_EXECUTE:SOP-CUSTOM-1", "idem-cas-race"))
                .thenReturn(stale);
        when(expiryTransitionExecutor.markCurrentExpiredProcessingUnknown(
                22L, "J4_PLAYBOOK_EXECUTE:SOP-CUSTOM-1", "idem-cas-race"))
                .thenReturn(0);
        when(expiryTransitionExecutor.loadCurrentCommitted(
                "J4_PLAYBOOK_EXECUTE:SOP-CUSTOM-1", "idem-cas-race"))
                .thenReturn(active);
        AtomicBoolean actionCalled = new AtomicBoolean(false);

        assertThatThrownBy(() -> service.execute(
                "J4_PLAYBOOK_EXECUTE:SOP-CUSTOM-1", "idem-cas-race", "hash-a", Map.class,
                () -> {
                    actionCalled.set(true);
                    return Map.of();
                }))
                .isInstanceOf(BizException.class)
                .hasMessage("IDEMPOTENCY_REQUEST_IN_PROGRESS");

        assertThat(actionCalled).isFalse();
        verify(expiryTransitionExecutor).markCurrentExpiredProcessingUnknown(
                22L, "J4_PLAYBOOK_EXECUTE:SOP-CUSTOM-1", "idem-cas-race");
    }

    @Test
    void exactExpiryCasMissReplaysAConcurrentKnownSuccessInsteadOfDowngradingIt() {
        AdminIdempotencyRecordEntity stale = existing("hash-success", "PROCESSING", null);
        stale.setId(23L);
        stale.setIsDeleted(0);
        AdminIdempotencyRecordEntity succeeded = existing("hash-success", "SUCCEEDED", "{\"approved\":true}");
        succeeded.setId(23L);
        when(recordMapper.selectActive("A6_ROLE_GRANTS:4214", "idem-cas-success"))
                .thenReturn(null);
        when(recordMapper.selectCurrent("A6_ROLE_GRANTS:4214", "idem-cas-success"))
                .thenReturn(stale);
        when(expiryTransitionExecutor.markCurrentExpiredProcessingUnknown(
                23L, "A6_ROLE_GRANTS:4214", "idem-cas-success"))
                .thenReturn(0);
        when(expiryTransitionExecutor.loadCurrentCommitted(
                "A6_ROLE_GRANTS:4214", "idem-cas-success"))
                .thenReturn(succeeded);

        Map<?, ?> result = service.execute(
                "A6_ROLE_GRANTS:4214", "idem-cas-success", "hash-success", Map.class, Map::of);

        assertThat(result.get("approved")).isEqualTo(true);
        verify(recordMapper, never()).resetExpiredById(any(), any(), any());
    }

    @Test
    void exactExpiryCasMissKeepsConcurrentKnownFailureRetryableForTheSamePayload() {
        AdminIdempotencyRecordEntity stale = existing("hash-failed", "PROCESSING", null);
        stale.setId(24L);
        stale.setIsDeleted(0);
        AdminIdempotencyRecordEntity failed = existing("hash-failed", "FAILED", null);
        failed.setId(24L);
        when(recordMapper.selectActive("A6_ROLE_GRANTS:4214", "idem-cas-failed"))
                .thenReturn(null);
        when(recordMapper.selectCurrent("A6_ROLE_GRANTS:4214", "idem-cas-failed"))
                .thenReturn(stale);
        when(expiryTransitionExecutor.markCurrentExpiredProcessingUnknown(
                24L, "A6_ROLE_GRANTS:4214", "idem-cas-failed"))
                .thenReturn(0);
        when(expiryTransitionExecutor.loadCurrentCommitted(
                "A6_ROLE_GRANTS:4214", "idem-cas-failed"))
                .thenReturn(failed);
        when(recordMapper.resetFailedById(eq(24L), eq("hash-failed"), any(LocalDateTime.class)))
                .thenReturn(1);

        Map<?, ?> result = service.execute(
                "A6_ROLE_GRANTS:4214", "idem-cas-failed", "hash-failed", Map.class,
                () -> Map.of("retried", true));

        assertThat(result.get("retried")).isEqualTo(true);
        verify(recordMapper).resetFailedById(eq(24L), eq("hash-failed"), any(LocalDateTime.class));
    }

    @Test
    void convertsOnlyExpiredProcessingRecordsToUnknownForSafeStartupRecovery() {
        AdminIdempotencyExpiryTransitionExecutor transitionExecutor =
                new AdminIdempotencyExpiryTransitionExecutor(recordMapper);
        when(recordMapper.lockExpiredProcessingBatch(200)).thenReturn(List.of(7L, 8L));
        when(recordMapper.markLockedExpiredProcessingUnknown(List.of(7L, 8L))).thenReturn(2);

        assertThat(transitionExecutor.markExpiredProcessingUnknownBatch(200)).isEqualTo(2);

        verify(recordMapper).lockExpiredProcessingBatch(200);
        verify(recordMapper).markLockedExpiredProcessingUnknown(List.of(7L, 8L));
    }

    @Test
    void unknownOutcomeForOneKeyDoesNotBlockASeparateNewKey() {
        AdminIdempotencyRecordEntity unknown = existing("hash-old", "UNKNOWN", null);
        unknown.setExpiresAt(LocalDateTime.of(2026, 6, 17, 0, 0));
        when(recordMapper.selectActive("A6_ROLE_GRANTS:4214", "idem-unknown"))
                .thenReturn(null);
        when(recordMapper.selectCurrent("A6_ROLE_GRANTS:4214", "idem-unknown"))
                .thenReturn(unknown);
        when(recordMapper.selectActive("A6_ROLE_GRANTS:4214", "idem-new"))
                .thenReturn(null);
        when(recordMapper.selectCurrent("A6_ROLE_GRANTS:4214", "idem-new"))
                .thenReturn(null);
        when(recordMapper.insert(any(AdminIdempotencyRecordEntity.class))).thenAnswer(invocation -> {
            invocation.<AdminIdempotencyRecordEntity>getArgument(0).setId(31L);
            return 1;
        });

        assertThatThrownBy(() -> service.execute(
                "A6_ROLE_GRANTS:4214", "idem-unknown", "hash-old", Map.class, Map::of))
                .isInstanceOf(BizException.class)
                .hasMessage("IDEMPOTENCY_RESULT_UNKNOWN");

        Map<?, ?> result = service.execute(
                "A6_ROLE_GRANTS:4214", "idem-new", "hash-new", Map.class,
                () -> Map.of("newKey", true));

        assertThat(result.get("newKey")).isEqualTo(true);
        verify(recordMapper).markSucceeded(eq(31L), org.mockito.ArgumentMatchers.contains("newKey"));
    }

    @Test
    void concurrentClaimAgainstAnUnknownOutcomeFailsClosedWithoutExecutingAgain() {
        AdminIdempotencyRecordEntity unknown = existing("hash-a", "UNKNOWN", null);
        when(recordMapper.selectActive("J4_PLAYBOOK_EXECUTE:SOP-CUSTOM-1", "idem-race"))
                .thenReturn(unknown);
        AtomicBoolean actionCalled = new AtomicBoolean(false);

        assertThatThrownBy(() -> service.execute(
                "J4_PLAYBOOK_EXECUTE:SOP-CUSTOM-1", "idem-race", "hash-a", Map.class,
                () -> {
                    actionCalled.set(true);
                    return Map.of();
                }))
                .isInstanceOf(BizException.class)
                .hasMessage("IDEMPOTENCY_RESULT_UNKNOWN");

        assertThat(actionCalled).isFalse();
        verify(recordMapper, never()).markSucceeded(any(), any());
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

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
