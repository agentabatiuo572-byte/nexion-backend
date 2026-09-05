package ffdd.opsconsole.developer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.developer.mapper.AppDeveloperAccessMapper;
import ffdd.opsconsole.developer.mapper.AppDeveloperApiKeyMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.time.LocalDateTime;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AppDeveloperApiServiceTest {
    @Test
    void approvedAccountCreatesOneTimeSecretAndStoresOnlyItsHash() {
        AppDeveloperAccessMapper access = mock(AppDeveloperAccessMapper.class);
        AppDeveloperApiKeyMapper keys = mock(AppDeveloperApiKeyMapper.class);
        when(access.lockUserSandbox(7L)).thenReturn(0);
        when(access.approved(7L, "PRODUCTION", "")).thenReturn(1);
        var created = new AppDeveloperApiKeyMapper.KeyRow(4L, "dak_4", 7L,
                AppDeveloperApiService.hash("build"), "build", "sk_live_sample", "wxyz",
                "ACTIVE", LocalDateTime.now(), null, "PRODUCTION", "");
        when(keys.insertKey(any())).thenReturn(1);
        when(keys.byIdempotency(7L, "PRODUCTION", "", "idem-1")).thenReturn(null, created);
        AuditLogService audit = mock(AuditLogService.class);

        var result = new AppDeveloperApiService(keys, access, new MockEnvironment(), executeSupplier(), audit)
                .createKey(7L, "build", "idem-1");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("status", "ACTIVE");
        assertThat(String.valueOf(result.getData().get("secret"))).matches("sk_live_[A-Za-z0-9_-]{43}");
        verify(keys).insertKey(argThat(write -> write.secretHash().length() == 64
                && !write.secretHash().contains("sk_live_")));
        verify(audit).recordRequired(any(AuditLogWriteRequest.class));
    }

    @Test
    void activeKeyAuthenticatesOnlyInsideItsApprovedAccountScope() {
        AppDeveloperAccessMapper access = mock(AppDeveloperAccessMapper.class);
        AppDeveloperApiKeyMapper keys = mock(AppDeveloperApiKeyMapper.class);
        String secret = "sk_live_" + java.util.UUID.randomUUID().toString().replace("-", "")
                + "fixture1234";
        var active = new AppDeveloperApiKeyMapper.KeyRow(4L, "dak_4", 7L, "request", "build",
                "sk_live_sample", "aaaa", "ACTIVE", LocalDateTime.now(), null, "PRODUCTION", "");
        when(keys.activeByHash(AppDeveloperApiService.hash(secret))).thenReturn(active);
        when(keys.touchLastUsed(4L)).thenReturn(1);
        when(access.userSandbox(7L)).thenReturn(0);
        when(access.approved(7L, "PRODUCTION", "")).thenReturn(1);

        var identity = new AppDeveloperApiService(keys, access, new MockEnvironment(),
                mock(AdminIdempotencyService.class), mock(AuditLogService.class)).authenticate(secret);

        assertThat(identity).containsEntry("userId", 7L).containsEntry("keyId", "dak_4");
        verify(keys).touchLastUsed(4L);
    }

    @Test
    void malformedKeyFailsClosedBeforeDatabaseLookup() {
        AppDeveloperAccessMapper access = mock(AppDeveloperAccessMapper.class);
        AppDeveloperApiKeyMapper keys = mock(AppDeveloperApiKeyMapper.class);

        assertThatThrownBy(() -> new AppDeveloperApiService(keys, access, new MockEnvironment(),
                mock(AdminIdempotencyService.class), mock(AuditLogService.class)).authenticate("sk_live_short"))
                .hasMessage("DEVELOPER_API_KEY_INVALID");
        verifyNoInteractions(access, keys);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void revokeUsesDurableIdempotencyAndRequiredAuditWithoutLeakingKeyMaterial() {
        AppDeveloperAccessMapper access = mock(AppDeveloperAccessMapper.class);
        AppDeveloperApiKeyMapper keys = mock(AppDeveloperApiKeyMapper.class);
        when(access.lockUserSandbox(7L)).thenReturn(0);
        when(access.approved(7L, "PRODUCTION", "")).thenReturn(1);
        var active = new AppDeveloperApiKeyMapper.KeyRow(4L, "key-4", 7L, "create-hash", "build",
                "sk_live_abc", "wxyz", "ACTIVE", LocalDateTime.now(), null, "PRODUCTION", "");
        var revoked = new AppDeveloperApiKeyMapper.KeyRow(4L, "key-4", 7L, "create-hash", "build",
                "sk_live_abc", "wxyz", "REVOKED", LocalDateTime.now(), LocalDateTime.now(), "PRODUCTION", "");
        when(keys.byId(4L, 7L, "PRODUCTION", "")).thenReturn(active, revoked);
        when(keys.revoke(4L, 7L, "PRODUCTION", "")).thenReturn(1);
        AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
        when(idempotency.execute(eq("APP_DEVELOPER_API_KEY_REVOKE:7:4"), eq("idem-revoke"), anyString(),
                eq(ApiResult.class), any())).thenAnswer(invocation -> ((Supplier) invocation.getArgument(4)).get());
        AuditLogService audit = mock(AuditLogService.class);

        var result = new AppDeveloperApiService(keys, access, new MockEnvironment(), idempotency, audit)
                .revoke(7L, 4L, "idem-revoke");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("status", "REVOKED").doesNotContainKey("secret");
        verify(audit).recordRequired(any(AuditLogWriteRequest.class));
    }

    @Test
    void revokeAuditFailurePropagatesRatherThanAcknowledgingAnUnauditedWrite() {
        AppDeveloperAccessMapper access = mock(AppDeveloperAccessMapper.class);
        AppDeveloperApiKeyMapper keys = mock(AppDeveloperApiKeyMapper.class);
        when(access.lockUserSandbox(7L)).thenReturn(0);
        when(access.approved(7L, "PRODUCTION", "")).thenReturn(1);
        var active = new AppDeveloperApiKeyMapper.KeyRow(4L, "key-4", 7L, "create-hash", "build",
                "sk_live_abc", "wxyz", "ACTIVE", LocalDateTime.now(), null, "PRODUCTION", "");
        when(keys.byId(4L, 7L, "PRODUCTION", "")).thenReturn(active);
        when(keys.revoke(4L, 7L, "PRODUCTION", "")).thenReturn(1);
        AuditLogService audit = mock(AuditLogService.class);
        doThrow(new IllegalStateException("audit unavailable")).when(audit).recordRequired(any());

        assertThatThrownBy(() -> new AppDeveloperApiService(keys, access, new MockEnvironment(), executeSupplier(), audit)
                .revoke(7L, 4L, "idem-failed-audit"))
                .hasMessage("audit unavailable");
        verify(audit).recordRequired(any());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private AdminIdempotencyService executeSupplier() {
        AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
        when(idempotency.execute(anyString(), anyString(), anyString(), eq(ApiResult.class), any()))
                .thenAnswer(invocation -> ((Supplier) invocation.getArgument(4)).get());
        return idempotency;
    }
}
