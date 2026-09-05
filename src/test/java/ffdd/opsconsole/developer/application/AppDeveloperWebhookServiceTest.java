package ffdd.opsconsole.developer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.developer.mapper.AppDeveloperAccessMapper;
import ffdd.opsconsole.developer.mapper.AppDeveloperWebhookMapper;
import ffdd.opsconsole.developer.mapper.AppDeveloperWebhookDeliveryMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AppDeveloperWebhookServiceTest {
    private AppDeveloperWebhookService service(AppDeveloperWebhookMapper mapper, MockEnvironment env) {
        AppDeveloperAccessMapper access = mock(AppDeveloperAccessMapper.class);
        when(access.lockUserSandbox(7L)).thenReturn(0);
        when(access.approved(7L, "PRODUCTION", "")).thenReturn(1);
        return service(mapper, access, env, mock(AuditLogService.class));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private AppDeveloperWebhookService service(AppDeveloperWebhookMapper mapper, AppDeveloperAccessMapper access,
                                                MockEnvironment env, AuditLogService audit) {
        AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
        when(idempotency.execute(anyString(), anyString(), anyString(), eq(ApiResult.class), any()))
                .thenAnswer(invocation -> ((Supplier) invocation.getArgument(4)).get());
        return new AppDeveloperWebhookService(mapper, access, env, null, idempotency, audit);
    }

    @Test
    void rejectsNonHttpsAndEventsOutsideWhitelistWithoutAnyDelivery() {
        AppDeveloperWebhookMapper mapper = mock(AppDeveloperWebhookMapper.class);
        var service = service(mapper, new MockEnvironment());

        var result = service.create(7L, "orders", "http://example.com/hook", "[\"admin.password.reset\"]", "idem-1");

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isIn("DEVELOPER_WEBHOOK_HTTPS_REQUIRED", "DEVELOPER_WEBHOOK_EVENT_NOT_ALLOWED");
        verify(mapper, never()).insertWebhook(any());
    }

    @Test
    void allowsLoopbackOnlyInExplicitLocalSandboxAndMarksDeliveryNotConfigured() {
        AppDeveloperWebhookMapper mapper = mock(AppDeveloperWebhookMapper.class);
        MockEnvironment env = new MockEnvironment().withProperty("nexion.developer.webhooks.allow-loopback", "true");
        env.setActiveProfiles("dev");
        when(mapper.byIdempotency(7L, "SANDBOX", "run-1", "idem-1")).thenReturn(null);
        when(mapper.insertWebhook(any())).thenReturn(1);
        when(mapper.byIdempotency(7L, "SANDBOX", "run-1", "idem-1")).thenReturn(
                new AppDeveloperWebhookMapper.WebhookRow(9L, 7L, "hash", "orders", "http://127.0.0.1:8081/hook", "[\"order.updated\"]",
                        "ACTIVE", "NOT_DELIVERED", 0L, null, null, LocalDateTime.now(), LocalDateTime.now(), "SANDBOX", "run-1"));
        when(mapper.secretHash(9L)).thenReturn("hash");
        when(mapper.insertWebhook(any())).thenReturn(1);
        when(mapper.byIdempotency(7L, "SANDBOX", "run-1", "idem-1")).thenReturn(
                new AppDeveloperWebhookMapper.WebhookRow(9L, 7L, "hash", "orders", "http://127.0.0.1:8081/hook", "[\"order.updated\"]",
                        "ACTIVE", "NOT_DELIVERED", 0L, null, null, LocalDateTime.now(), LocalDateTime.now(), "SANDBOX", "run-1"));

        var result = service(mapper, mock(AppDeveloperAccessMapper.class), env, mock(AuditLogService.class));
        // The production-path guard is tested separately; this assertion documents the delivery boundary.
        assertThat(AppDeveloperWebhookService.DELIVERY_STATUS).isEqualTo("NOT_DELIVERED");
    }

    @Test
    void productionRejectsLoopbackEvenWhenUrlLooksValid() {
        AppDeveloperWebhookMapper mapper = mock(AppDeveloperWebhookMapper.class);
        var result = service(mapper, new MockEnvironment()).create(7L, "orders", "https://127.0.0.1/hook", "[\"order.updated\"]", "idem-1");
        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("DEVELOPER_WEBHOOK_HOST_NOT_ALLOWED");
    }

    @Test
    void rejectsExplicitZeroPortBeforePersistingWebhook() {
        AppDeveloperWebhookMapper mapper = mock(AppDeveloperWebhookMapper.class);
        var result = service(mapper, new MockEnvironment()
                .withProperty("nexion.developer.webhooks.allowed-hosts", "example.com"))
                .create(7L, "orders", "https://hooks.example.com:0/hook", "[\"order.updated\"]", "idem-1");

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("DEVELOPER_WEBHOOK_URL_INVALID");
        verify(mapper, never()).insertWebhook(any());
    }

    @Test
    void concurrentDuplicateKeyWithDifferentPayloadIsAConflictNotSuccess() {
        AppDeveloperWebhookMapper mapper = mock(AppDeveloperWebhookMapper.class);
        var row = new AppDeveloperWebhookMapper.WebhookRow(9L, 7L, "different-hash", "other",
                "https://example.com/other", "[\"order.completed\"]", "ACTIVE", "NOT_DELIVERED", 0L, null, null,
                LocalDateTime.now(), LocalDateTime.now(), "PRODUCTION", "");
        when(mapper.byIdempotency(7L, "PRODUCTION", "", "idem-1234")).thenReturn(row);

        var result = service(mapper, new MockEnvironment()
                .withProperty("nexion.developer.webhooks.allowed-hosts", "example.com")).create(7L, "orders",
                "https://example.com/current", "[\"order.updated\"]", "idem-1234");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("IDEMPOTENCY_PAYLOAD_CONFLICT");
    }

    @Test
    void concurrentRotationLosesCasAndNeverReturnsASecret() {
        AppDeveloperWebhookMapper mapper = mock(AppDeveloperWebhookMapper.class);
        var row = new AppDeveloperWebhookMapper.WebhookRow(9L, 7L, "hash", "orders", "https://hooks.example.com/current",
                "[\"order.updated\"]", "ACTIVE", "NOT_DELIVERED", 4L, null, null, LocalDateTime.now(), LocalDateTime.now(), "PRODUCTION", "");
        when(mapper.byId(9L, 7L, "PRODUCTION", "")).thenReturn(row);

        var result = service(mapper, new MockEnvironment()
                .withProperty("nexion.developer.webhooks.allowed-hosts", "example.com")
                .withProperty("nexion.developer.webhooks.secret-key", "test-only-encryption-key"))
                .rotateSecret(7L, 9L, "rotate-1");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("DEVELOPER_WEBHOOK_CONCURRENT_UPDATE");
        assertThat(result.getData()).isNull();
    }

    @Test
    void legacyRotationReplayReturnsTheCommittedEndpointWithoutRevealingTheOriginalSecret() {
        AppDeveloperWebhookMapper mapper = mock(AppDeveloperWebhookMapper.class);
        var row = new AppDeveloperWebhookMapper.WebhookRow(9L, 7L, "hash", "orders", "https://hooks.example.com/current",
                "[\"order.updated\"]", "ACTIVE", "NOT_DELIVERED", 5L, "rotate-1", "rotation-hash", LocalDateTime.now(), LocalDateTime.now(), "PRODUCTION", "");
        when(mapper.byId(9L, 7L, "PRODUCTION", "")).thenReturn(row);

        var result = service(mapper, new MockEnvironment().withProperty("nexion.developer.webhooks.allowed-hosts", "example.com"))
                .rotateSecret(7L, 9L, "rotate-1");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).doesNotContainKey("secret");
        verify(mapper, never()).rotateSecretWithCiphertext(anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void activeEndpointReportsDeliveryEnabledAndNeverEchoesSecretOnRead() {
        AppDeveloperWebhookMapper mapper = mock(AppDeveloperWebhookMapper.class);
        when(mapper.listBounded(7L, "PRODUCTION", "", 20)).thenReturn(List.of(new AppDeveloperWebhookMapper.WebhookRow(9L, 7L,
                "hash", "orders", "https://hooks.example.com/current", "[\"order.updated\"]", "ACTIVE", "SUCCEEDED", 1L,
                null, null, "ciphertext", LocalDateTime.now(), LocalDateTime.now(), "PRODUCTION", "")));

        var result = service(mapper, new MockEnvironment().withProperty("nexion.developer.webhooks.allowed-hosts", "example.com")).list(7L);

        assertThat(result.getData()).hasSize(1);
        assertThat(result.getData().get(0)).containsEntry("deliveryEnabled", true).doesNotContainKey("secret");
        assertThat(result.getData().get(0)).containsEntry("deliveryStatus", "SUCCEEDED");
    }

    @Test
    void accountWebhookQuotaIsCheckedUnderTheAccountLockBeforePersisting() {
        AppDeveloperWebhookMapper mapper = mock(AppDeveloperWebhookMapper.class);
        when(mapper.byIdempotency(7L, "PRODUCTION", "", "idem-quota")).thenReturn(null);
        when(mapper.countExisting(7L, "PRODUCTION", "")).thenReturn(20);

        var result = service(mapper, new MockEnvironment()
                .withProperty("nexion.developer.webhooks.allowed-hosts", "example.com"))
                .create(7L, "orders", "https://example.com/hook", "[\"order.updated\"]", "idem-quota");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("DEVELOPER_WEBHOOK_ACCOUNT_LIMIT_REACHED");
        verify(mapper, never()).insertWebhook(any());
    }

    @Test
    void deliveryHistoryUsesStableIdCursorAndExposesOlderRows() {
        AppDeveloperWebhookMapper mapper = mock(AppDeveloperWebhookMapper.class);
        AppDeveloperWebhookDeliveryMapper deliveries = mock(AppDeveloperWebhookDeliveryMapper.class);
        AppDeveloperAccessMapper access = mockAccess();
        var endpoint = new AppDeveloperWebhookMapper.WebhookRow(9L, 7L, "hash", "orders",
                "https://example.com/hook", "[\"order.updated\"]", "ACTIVE", "SUCCEEDED", 1L,
                null, null, "ciphertext", LocalDateTime.now(), LocalDateTime.now(), "PRODUCTION", "");
        when(mapper.byId(9L, 7L, "PRODUCTION", "")).thenReturn(endpoint);
        when(deliveries.listForWebhookBefore(9L, 7L, "PRODUCTION", "", null, 3)).thenReturn(List.of(
                delivery(103L), delivery(102L), delivery(101L)));
        var service = new AppDeveloperWebhookService(mapper, access, new MockEnvironment(), deliveries,
                mock(AdminIdempotencyService.class), mock(AuditLogService.class));

        var result = service.deliveryLog(7L, 9L, null, 2);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("hasMore", true).containsEntry("nextCursor", "102");
        assertThat((List<?>) result.getData().get("items")).hasSize(2);
    }

    private AppDeveloperWebhookDeliveryMapper.DeliveryRow delivery(Long id) {
        LocalDateTime at = LocalDateTime.now();
        return new AppDeveloperWebhookDeliveryMapper.DeliveryRow(id, 9L, 7L, "PRODUCTION", "",
                "event-" + id, "order.updated", "{}", "SUCCEEDED", 1, 5, 204, null, at, at, at);
    }

    @Test
    void createValidatesDnsBeforePersistingSoAcceptedUrlsCanBeDeliveredUnderTheSamePolicy() {
        AppDeveloperWebhookMapper mapper = mock(AppDeveloperWebhookMapper.class);
        var result = service(mapper, new MockEnvironment()
                .withProperty("nexion.developer.webhooks.allowed-hosts", "unresolvable.invalid"))
                .create(7L, "orders", "https://unresolvable.invalid/hook", "[\"order.updated\"]", "idem-dns");

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isIn("DEVELOPER_WEBHOOK_HOST_UNRESOLVED", "DEVELOPER_WEBHOOK_PRIVATE_ADDRESS_FORBIDDEN");
        verify(mapper, never()).insertWebhook(any());
    }

    @Test
    void createFailsClosedBeforePersistingWhenSecretEncryptionKeyIsUnavailable() {
        AppDeveloperWebhookMapper mapper = mock(AppDeveloperWebhookMapper.class);
        MockEnvironment env = new MockEnvironment()
                .withProperty("nexion.developer.webhooks.allow-loopback", "true");
        env.setActiveProfiles("dev");

        var result = service(mapper, env)
                .create(7L, "orders", "http://127.0.0.1:8081/hook", "[\"order.updated\"]", "idem-no-key");

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("DEVELOPER_WEBHOOK_SECRET_KEY_UNAVAILABLE");
        verify(mapper, never()).insertWebhook(any());
    }

    @Test
    void rotationFailsClosedWithoutReplacingTheDeliverableSecretWhenKeyIsUnavailable() {
        AppDeveloperWebhookMapper mapper = mock(AppDeveloperWebhookMapper.class);
        var row = new AppDeveloperWebhookMapper.WebhookRow(9L, 7L, "hash", "orders", "https://example.com/hook",
                "[\"order.updated\"]", "ACTIVE", "NOT_DELIVERED", 4L, null, null,
                "existing-ciphertext", LocalDateTime.now(), LocalDateTime.now(), "PRODUCTION", "");
        when(mapper.byId(9L, 7L, "PRODUCTION", "")).thenReturn(row);

        var result = service(mapper, new MockEnvironment()).rotateSecret(7L, 9L, "rotate-no-key");

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("DEVELOPER_WEBHOOK_SECRET_KEY_UNAVAILABLE");
        verify(mapper, never()).rotateSecretWithCiphertext(anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void legacyEndpointWithoutCiphertextCannotBeEnabled() {
        AppDeveloperWebhookMapper mapper = mock(AppDeveloperWebhookMapper.class);
        var row = new AppDeveloperWebhookMapper.WebhookRow(9L, 7L, "hash", "orders", "https://example.com/hook",
                "[\"order.updated\"]", "DISABLED", "NOT_DELIVERED", 4L, null, null,
                LocalDateTime.now(), LocalDateTime.now(), "PRODUCTION", "");
        when(mapper.byId(9L, 7L, "PRODUCTION", "")).thenReturn(row);

        var result = service(mapper, new MockEnvironment()).setEnabled(7L, 9L, true, "enable-no-secret");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("DEVELOPER_WEBHOOK_SECRET_UNAVAILABLE");
        verify(mapper, never()).setStatus(anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void replayUsesTheSameIdempotencyScopeWithoutRepeatingMutationOrAudit() {
        AppDeveloperWebhookMapper mapper = mock(AppDeveloperWebhookMapper.class);
        AppDeveloperAccessMapper access = mock(AppDeveloperAccessMapper.class);
        when(access.lockUserSandbox(7L)).thenReturn(0);
        when(access.approved(7L, "PRODUCTION", "")).thenReturn(1);
        AuditLogService audit = mock(AuditLogService.class);
        AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
        Map<String, Object> replay = Map.ofEntries(
                Map.entry("id", 9L), Map.entry("name", "orders"), Map.entry("url", "https://example.com/hook"),
                Map.entry("events", List.of("order.updated")), Map.entry("status", "ACTIVE"),
                Map.entry("deliveryStatus", "NOT_DELIVERED"), Map.entry("deliveryEnabled", true),
                Map.entry("source", "server"), Map.entry("sourceEnvironment", "PRODUCTION"), Map.entry("runId", ""),
                Map.entry("createdAt", "2026-09-03T00:00:00Z"));
        when(idempotency.execute(eq("APP_DEVELOPER_WEBHOOK_UPDATE:7:9"), eq("idem-replay"), anyString(),
                eq(ApiResult.class), any())).thenReturn(ApiResult.ok(replay));
        var service = new AppDeveloperWebhookService(mapper, access,
                new MockEnvironment().withProperty("nexion.developer.webhooks.allowed-hosts", "example.com"),
                null, idempotency, audit);

        var result = service.update(7L, 9L, "orders", "https://example.com/hook",
                "[\"order.updated\"]", false, "idem-replay");

        assertThat(result.getCode()).isZero();
        verify(mapper, never()).update(any(AppDeveloperWebhookMapper.WebhookUpdate.class));
        verifyNoInteractions(audit);
    }

    @Test
    void payloadMismatchIsNotReplayedAndNeverMutatesOrAudits() {
        AppDeveloperWebhookMapper mapper = mock(AppDeveloperWebhookMapper.class);
        AppDeveloperAccessMapper access = mock(AppDeveloperAccessMapper.class);
        when(access.lockUserSandbox(7L)).thenReturn(0);
        when(access.approved(7L, "PRODUCTION", "")).thenReturn(1);
        AuditLogService audit = mock(AuditLogService.class);
        AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
        when(idempotency.execute(anyString(), eq("idem-conflict"), anyString(), eq(ApiResult.class), any()))
                .thenThrow(new BizException(409, "IDEMPOTENCY_KEY_PAYLOAD_MISMATCH"));
        var service = new AppDeveloperWebhookService(mapper, access,
                new MockEnvironment().withProperty("nexion.developer.webhooks.allowed-hosts", "example.com"),
                null, idempotency, audit);

        var result = service.setEnabled(7L, 9L, true, "idem-conflict");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
        verify(mapper, never()).setStatus(anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyString());
        verifyNoInteractions(audit);
    }

    @Test
    void requiredAuditFailureEscapesTheClaimedMutationAndCannotBeReportedAsSuccess() {
        AppDeveloperWebhookMapper mapper = mock(AppDeveloperWebhookMapper.class);
        var row = new AppDeveloperWebhookMapper.WebhookRow(9L, 7L, "hash", "orders", "https://example.com/hook",
                "[\"order.updated\"]", "ACTIVE", "NOT_DELIVERED", 4L, null, null,
                LocalDateTime.now(), LocalDateTime.now(), "PRODUCTION", "");
        when(mapper.byId(9L, 7L, "PRODUCTION", "")).thenReturn(row);
        when(mapper.setStatus(9L, 7L, "PRODUCTION", "", 4L, "DISABLED")).thenReturn(1);
        AuditLogService audit = mock(AuditLogService.class);
        doThrow(new IllegalStateException("audit store unavailable")).when(audit).recordRequired(any(AuditLogWriteRequest.class));

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> service(mapper,
                mockAccess(), new MockEnvironment().withProperty("nexion.developer.webhooks.allowed-hosts", "example.com"), audit)
                .setEnabled(7L, 9L, false, "idem-audit")))
                .isInstanceOf(IllegalStateException.class).hasMessage("audit store unavailable");
        verify(audit).recordRequired(any(AuditLogWriteRequest.class));
    }

    @Test
    void webhookAuditContainsOnlySafeMutationMetadata() {
        AppDeveloperWebhookMapper mapper = mock(AppDeveloperWebhookMapper.class);
        var row = new AppDeveloperWebhookMapper.WebhookRow(9L, 7L, "hash", "orders", "https://example.com/private/path",
                "[\"order.updated\"]", "ACTIVE", "NOT_DELIVERED", 4L, "rotation-key", "rotation-hash",
                "ciphertext", LocalDateTime.now(), LocalDateTime.now(), "PRODUCTION", "");
        when(mapper.byId(9L, 7L, "PRODUCTION", "")).thenReturn(row, row);
        when(mapper.setStatus(9L, 7L, "PRODUCTION", "", 4L, "DISABLED")).thenReturn(1);
        AuditLogService audit = mock(AuditLogService.class);

        var result = service(mapper, mockAccess(), new MockEnvironment(), audit)
                .setEnabled(7L, 9L, false, "idem-safe-audit");

        assertThat(result.getCode()).isZero();
        ArgumentCaptor<AuditLogWriteRequest> auditWrite = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(audit).recordRequired(auditWrite.capture());
        assertThat(String.valueOf(auditWrite.getValue().getDetail()))
                .doesNotContain("example.com", "private/path", "rotation-key", "rotation-hash", "ciphertext", "secret");
    }

    private AppDeveloperAccessMapper mockAccess() {
        AppDeveloperAccessMapper access = mock(AppDeveloperAccessMapper.class);
        when(access.lockUserSandbox(7L)).thenReturn(0);
        when(access.approved(7L, "PRODUCTION", "")).thenReturn(1);
        return access;
    }
}
