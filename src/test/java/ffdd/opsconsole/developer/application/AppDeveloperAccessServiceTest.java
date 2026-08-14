package ffdd.opsconsole.developer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.developer.mapper.AppDeveloperAccessMapper;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AppDeveloperAccessServiceTest {
    @Test
    void createsAndReplaysOneRequest() {
        AppDeveloperAccessMapper mapper = mock(AppDeveloperAccessMapper.class);
        AtomicReference<String> requestHash = new AtomicReference<>();
        when(mapper.lockUserSandbox(7L)).thenReturn(0);
        when(mapper.findByKey(7L, "PRODUCTION", "", "key-1")).thenAnswer(ignored -> requestHash.get() == null
                ? null
                : new AppDeveloperAccessMapper.AccessRow(
                        "DEV-1", 7L, "key-1", requestHash.get(), "PENDING", LocalDateTime.of(2026, 8, 13, 0, 0),
                        "PRODUCTION", ""));
        when(mapper.insertRequest(any())).thenAnswer(invocation -> {
            requestHash.set(invocation.getArgument(0, AppDeveloperAccessMapper.AccessWrite.class).requestHash());
            return 1;
        });
        var result = new AppDeveloperAccessService(mapper, new MockEnvironment()).submit(
                7L, "Nexion", "dev@example.com", "Inference workloads", "key-1");
        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("status", "PENDING");
        assertThat(result.getData()).containsEntry("requestNo", "DEV-1");
    }

    @Test
    void existingPendingRequestPreventsASecondBusinessIntent() {
        AppDeveloperAccessMapper mapper = mock(AppDeveloperAccessMapper.class);
        when(mapper.lockUserSandbox(7L)).thenReturn(0);
        when(mapper.pending(7L, "PRODUCTION", "")).thenReturn(new AppDeveloperAccessMapper.AccessRow(
                "DEV-OLD", 7L, "old-key", "different-hash", "PENDING",
                LocalDateTime.of(2026, 8, 13, 0, 0), "PRODUCTION", ""));
        var result = new AppDeveloperAccessService(mapper, new MockEnvironment()).submit(
                7L, "Nexion", "dev@example.com", "Inference workloads", "new-key");
        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("DEVELOPER_ACCESS_REQUEST_PENDING");
        verify(mapper, never()).insertRequest(any());
    }

    @Test
    void sandboxRequestIsBoundToTheServerOwnedRun() {
        AppDeveloperAccessMapper mapper = mock(AppDeveloperAccessMapper.class);
        AtomicReference<String> requestHash = new AtomicReference<>();
        when(mapper.lockUserSandbox(7L)).thenReturn(1);
        when(mapper.findByKey(7L, "SANDBOX", "run-1", "key-1")).thenAnswer(ignored -> requestHash.get() == null
                ? null : new AppDeveloperAccessMapper.AccessRow(
                "DEV-1", 7L, "key-1", requestHash.get(), "PENDING",
                LocalDateTime.of(2026, 8, 13, 0, 0), "SANDBOX", "run-1"));
        when(mapper.insertRequest(any())).thenAnswer(invocation -> {
            var write = invocation.getArgument(0, AppDeveloperAccessMapper.AccessWrite.class);
            requestHash.set(write.requestHash());
            assertThat(write.sourceEnvironment()).isEqualTo("SANDBOX");
            assertThat(write.runId()).isEqualTo("run-1");
            return 1;
        });
        MockEnvironment environment = new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID", "run-1");
        environment.setActiveProfiles("local-sandbox");
        var result = new AppDeveloperAccessService(mapper, environment).submit(
                7L, "Nexion", "dev@example.com", "Inference workloads", "key-1");
        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("sourceEnvironment", "SANDBOX").containsEntry("runId", "run-1");
    }
}
