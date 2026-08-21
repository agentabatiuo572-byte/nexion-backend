package ffdd.opsconsole.developer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.developer.mapper.AppDeveloperAccessMapper;
import ffdd.opsconsole.developer.mapper.AppDeveloperApiKeyMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AppDeveloperApiServiceTest {
    @Test
    void refusesManagementUntilAccessRequestIsApproved() {
        AppDeveloperAccessMapper access = mock(AppDeveloperAccessMapper.class);
        AppDeveloperApiKeyMapper keys = mock(AppDeveloperApiKeyMapper.class);
        when(access.lockUserSandbox(7L)).thenReturn(0);
        when(access.approved(7L, "PRODUCTION", "")).thenReturn(0);

        var result = new AppDeveloperApiService(keys, access, new MockEnvironment())
                .createKey(7L, "build", "idem-1");

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("DEVELOPER_ACCESS_APPROVAL_REQUIRED");
        verify(keys, never()).insertKey(any());
    }

    @Test
    void createsSecretOnceAndPersistsOnlyHashPrefixAndLast4() {
        AppDeveloperAccessMapper access = mock(AppDeveloperAccessMapper.class);
        AppDeveloperApiKeyMapper keys = mock(AppDeveloperApiKeyMapper.class);
        when(access.lockUserSandbox(7L)).thenReturn(0);
        when(access.approved(7L, "PRODUCTION", "")).thenReturn(1);
        when(keys.byIdempotency(7L, "PRODUCTION", "", "idem-1")).thenReturn(null);
        AtomicReference<AppDeveloperApiKeyMapper.KeyWrite> write = new AtomicReference<>();
        when(keys.insertKey(any())).thenAnswer(invocation -> { write.set(invocation.getArgument(0)); return 1; });
        when(keys.byIdempotency(7L, "PRODUCTION", "", "idem-1")).thenAnswer(invocation -> {
            var w = write.get();
            return w == null ? null : new AppDeveloperApiKeyMapper.KeyRow(31L, "key-31", 7L, w.requestHash(),
                    w.name(), w.prefix(), w.last4(), "ACTIVE", LocalDateTime.of(2026, 8, 16, 0, 0), null, "PRODUCTION", "");
        });

        var result = new AppDeveloperApiService(keys, access, new MockEnvironment())
                .createKey(7L, "build", "idem-1");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsKeys("id", "secret", "prefix", "last4");
        assertThat(String.valueOf(result.getData().get("secret"))).startsWith("sk_");
        assertThat(write.get().secretHash()).doesNotContain(String.valueOf(result.getData().get("secret")));
        assertThat(write.get().prefix()).isNotBlank();
        assertThat(write.get().last4()).hasSize(4);
        assertThat(write.get().secretHash()).hasSize(64);
    }

    @Test
    void replayDoesNotCreateAnotherKeyOrRevealSecretAgain() {
        AppDeveloperAccessMapper access = mock(AppDeveloperAccessMapper.class);
        AppDeveloperApiKeyMapper keys = mock(AppDeveloperApiKeyMapper.class);
        when(access.lockUserSandbox(7L)).thenReturn(0);
        when(access.approved(7L, "PRODUCTION", "")).thenReturn(1);
        var row = new AppDeveloperApiKeyMapper.KeyRow(31L, "key-31", 7L, AppDeveloperApiService.hash("build"), "build", "sk_live_abc", "wxyz",
                "ACTIVE", LocalDateTime.of(2026, 8, 16, 0, 0), null, "PRODUCTION", "");
        when(keys.byIdempotency(7L, "PRODUCTION", "", "idem-1")).thenReturn(row);

        var result = new AppDeveloperApiService(keys, access, new MockEnvironment())
                .createKey(7L, "build", "idem-1");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).doesNotContainKey("secret");
        verify(keys, never()).insertKey(any());
    }

    @Test
    void apiKeyAuthenticationIsHashBasedAndRevokedKeysFail() {
        AppDeveloperAccessMapper access = mock(AppDeveloperAccessMapper.class);
        AppDeveloperApiKeyMapper keys = mock(AppDeveloperApiKeyMapper.class);
        when(keys.activeByHash(any())).thenReturn(new AppDeveloperApiKeyMapper.KeyRow(31L, "key-31", 7L, "hash", "build",
                "sk_live_abc", "wxyz", "ACTIVE", LocalDateTime.now(), null, "PRODUCTION", ""));
        when(access.userSandbox(7L)).thenReturn(0);
        when(access.approved(7L, "PRODUCTION", "")).thenReturn(1);

        var result = new AppDeveloperApiService(keys, access, new MockEnvironment()).authenticate("sk_live_secret_123456");

        assertThat(result).containsEntry("userId", 7L).containsEntry("keyId", "key-31");
        verify(keys).touchLastUsed(31L);
    }
}
